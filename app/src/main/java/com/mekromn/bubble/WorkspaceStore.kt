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
    val lastNotice: String = "")
internal data class StoredWorkspace(val selected: String, val tabs: List<StoredTab>,
    val bubbleX: Float = 0.88f, val bubbleY: Float = 0.3f)

/** Single ordered IO lane; immutable snapshots; atomic rename; never serialize Activity/View. */
internal class WorkspaceStore(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-state").apply { isDaemon = true } }
    private val file = AtomicFile(File(context.filesDir, "workspace-v2.json"))
    private var writable = true
    fun load(done: (StoredWorkspace?, String?) -> Unit) {
        io.execute {
            var result: StoredWorkspace? = null
            var error: String? = null
            try {
                if (file.baseFile.exists()) {
                    require(file.baseFile.length() <= 20L * 1024 * 1024)
                    val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                    require(json.getInt("version") == 1)
                    val array = json.getJSONArray("tabs")
                    val seen = HashSet<String>()
                    val tabs = ArrayList<StoredTab>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val id = item.getString("id")
                        UUID.fromString(id)
                        require(seen.add(id))
                        val url = item.getString("url")
                        if (!Policy.isWeb(url)) continue
                        tabs += StoredTab(id, url, item.optString("title").take(512),
                            item.optBoolean("desktop"), item.optString("state").takeIf { it.length in 1..524288 },
                            item.optBoolean("unread"), item.optString("lastNotice").take(128))
                    }
                    result = StoredWorkspace(json.optString("selected"), tabs,
                        json.optDouble("x", 0.88).toFloat(), json.optDouble("y", 0.3).toFloat())
                }
            } catch (_: Exception) {
                // Preserve evidence/old data. A corrupt file must not silently become an empty save.
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
                    val tabs = JSONArray()
                    snapshot.tabs.forEach { tab ->
                        val item = JSONObject().put("id", tab.id).put("url", tab.url)
                            .put("title", tab.title.take(512)).put("desktop", tab.desktop)
                            .put("unread", tab.unread).put("lastNotice", tab.lastNotice)
                        val state = tab.state
                        if (state != null && state.length <= 524288 && state.length <= remaining) {
                            remaining -= state.length
                            item.put("state", state)
                        }
                        tabs.put(item)
                    }
                    val bytes = JSONObject().put("version", 1).put("selected", snapshot.selected)
                        .put("x", snapshot.bubbleX.toDouble()).put("y", snapshot.bubbleY.toDouble())
                        .put("tabs", tabs).toString().toByteArray()
                    require(bytes.size <= 20 * 1024 * 1024)
                    stream = file.startWrite()
                    stream.write(bytes)
                    file.finishWrite(stream)
                    success = true
                } catch (_: Exception) { if (stream != null) file.failWrite(stream) }
            }
            if (done != null) main.post { done(success) }
        }
    }
}
