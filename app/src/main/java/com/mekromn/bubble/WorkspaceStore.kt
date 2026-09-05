package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal data class StoredTab(val id: String, val url: String, val title: String,
    val desktop: Boolean = false, val state: String? = null, val unread: Boolean = false,
    val lastNotice: String = "", val localName: String = "", val pinned: Boolean = false,
    val note: String = "", val muted: Boolean = false)
internal data class StoredWorkspace(val selected: String, val tabs: List<StoredTab>,
    val bubbleX: Float = 0.88f, val bubbleY: Float = 0.3f,
    val windowX: Float = .5f, val windowY: Float = .25f,
    val windowWidth: Float = .92f, val windowHeight: Float = .72f,
    val closedTabs: List<StoredTab> = emptyList(), val prompts: List<PromptSnippet> = StarterPrompts.items())

/** One ordered IO lane; immutable snapshots; atomic rename. Existing version-1 files remain valid. */
internal class WorkspaceStore(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-state").apply { isDaemon = true } }
    private val file = AtomicFile(File(context.filesDir, "workspace-v2.json"))
    private var writable = true
    private fun readTab(item: JSONObject): StoredTab? {
        val id = item.getString("id"); UUID.fromString(id)
        val url = item.getString("url")
        if (!Policy.isWeb(url)) return null
        return StoredTab(id, url, item.optString("title").take(512), item.optBoolean("desktop"),
            item.optString("state").takeIf { it.length in 1..524288 }, item.optBoolean("unread"),
            item.optString("lastNotice").take(128), QuickTabPolicy.localName(item.optString("localName")),
            item.optBoolean("pinned"), item.optString("note").take(16384), item.optBoolean("muted"))
    }
    fun load(done: (StoredWorkspace?, String?) -> Unit) {
        io.execute {
            var result: StoredWorkspace? = null
            var error: String? = null
            try {
                if (file.baseFile.exists()) {
                    require(file.baseFile.length() <= 20L * 1024 * 1024)
                    val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                    require(json.getInt("version") == 1)
                    val seen = HashSet<String>()
                    fun readTabs(array: JSONArray, maximum: Int = array.length()): List<StoredTab> {
                        val tabs = ArrayList<StoredTab>()
                        for (i in 0 until minOf(array.length(), maximum)) {
                            val tab = readTab(array.getJSONObject(i)) ?: continue
                            require(seen.add(tab.id))
                            tabs += tab
                        }
                        return tabs
                    }
                    val tabs = readTabs(json.getJSONArray("tabs"))
                    // Only closed-history retention is bounded; there is NO open-tab limit.
                    val closed = readTabs(json.optJSONArray("closedTabs") ?: JSONArray(), 20)
                    val snippets = json.optJSONArray("prompts")?.let { array ->
                        val ids = HashSet<String>()
                        (0 until array.length()).map { i ->
                            val item = array.getJSONObject(i)
                            val id = item.getString("id").take(128)
                            require(id.isNotBlank() && ids.add(id))
                            PromptSnippet(id, QuickTabPolicy.localName(item.getString("title")), item.getString("body").take(16384))
                        }
                    } ?: StarterPrompts.items()
                    result = StoredWorkspace(json.optString("selected"), tabs,
                        json.optDouble("x", .88).toFloat(), json.optDouble("y", .3).toFloat(),
                        json.optDouble("windowX", .5).toFloat(), json.optDouble("windowY", .25).toFloat(),
                        json.optDouble("windowWidth", .92).toFloat(), json.optDouble("windowHeight", .72).toFloat(), closed, snippets)
                }
            } catch (_: Exception) {
                writable = false
                error = "Saved workspace could not be read. Its file is preserved; this session will not overwrite it."
            }
            main.post { done(result, error) }
        }
    }
    fun save(snapshot: StoredWorkspace, done: ((Boolean) -> Unit)? = null) {
        io.execute {
            var success = false
            if (writable) {
                var stream: java.io.FileOutputStream? = null
                try {
                    var remaining = 8 * 1024 * 1024
                    fun writeTabs(tabs: List<StoredTab>): JSONArray {
                        val array = JSONArray()
                        tabs.forEach { tab ->
                            val item = JSONObject().put("id", tab.id).put("url", tab.url).put("title", tab.title.take(512))
                                .put("desktop", tab.desktop).put("unread", tab.unread).put("lastNotice", tab.lastNotice)
                                .put("localName", tab.localName).put("pinned", tab.pinned).put("note", tab.note.take(16384)).put("muted", tab.muted)
                            val state = tab.state
                            if (state != null && state.length <= 524288 && state.length <= remaining) {
                                remaining -= state.length; item.put("state", state)
                            }
                            array.put(item)
                        }
                        return array
                    }
                    val tabs = writeTabs(snapshot.tabs)
                    val closed = writeTabs(snapshot.closedTabs.take(20))
                    val snippets = JSONArray()
                    snapshot.prompts.forEach { snippets.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body.take(16384))) }
                    val bytes = JSONObject().put("version", 1).put("selected", snapshot.selected)
                        .put("x", snapshot.bubbleX.toDouble()).put("y", snapshot.bubbleY.toDouble())
                        .put("windowX", snapshot.windowX.toDouble()).put("windowY", snapshot.windowY.toDouble())
                        .put("windowWidth", snapshot.windowWidth.toDouble()).put("windowHeight", snapshot.windowHeight.toDouble())
                        .put("tabs", tabs).put("closedTabs", closed).put("prompts", snippets).toString().toByteArray()
                    require(bytes.size <= 20 * 1024 * 1024)
                    stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); success = true
                } catch (_: Exception) { if (stream != null) file.failWrite(stream) }
            }
            if (done != null) main.post { done(success) }
        }
    }
}
