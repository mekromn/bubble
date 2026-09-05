package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Independent optional UI preferences: all reads/writes use an ordered worker. No tab migration. */
internal class AccessPreferences private constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-edge-settings").apply { isDaemon = true } }
    private val file = AtomicFile(File(context.filesDir, "edge-access-v1.json"))
    private val listeners = LinkedHashSet<() -> Unit>()
    var options = EdgeOptions()
        private set
    var ready = false
        private set
    var error: String? = null
        private set
    private var writable = true
    init {
        io.execute {
            var loaded = EdgeOptions()
            var failure: String? = null
            try {
                // openRead lets AtomicFile recover its backup before deciding that no file exists.
                val text = try { file.openRead().bufferedReader().use { it.readText() } }
                    catch (_: java.io.FileNotFoundException) { null }
                if (text != null) {
                    require(text.length < 16384)
                    val json = JSONObject(text); require(json.getInt("version") == 1)
                    loaded = EdgeOptions(json.optBoolean("enabled"), json.optBoolean("left"),
                        json.optDouble("position", .5).toFloat(), json.optInt("height", 104),
                        json.optInt("width", 18), json.optBoolean("indicator", true)).sanitized()
                }
            } catch (_: Exception) {
                writable = false
                failure = "Edge settings could not be read. The file is preserved; bubble mode remains available."
            }
            main.post { options = loaded; error = failure; ready = true; notifyListeners() }
        }
    }
    fun listen(listener: () -> Unit) { listeners += listener; if (ready) listener() }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    fun update(value: EdgeOptions, done: ((Boolean) -> Unit)? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!ready) { done?.invoke(false); return }
        val clean = value.sanitized()
        options = clean; notifyListeners()
        io.execute {
            var stream: java.io.FileOutputStream? = null
            var success = false
            try {
                check(writable)
                val bytes = JSONObject().put("version", 1).put("enabled", clean.enabled).put("left", clean.left)
                    .put("position", clean.position.toDouble()).put("height", clean.heightDp)
                    .put("width", clean.widthDp).put("indicator", clean.indicator).toString().toByteArray()
                stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); success = true
            } catch (_: Exception) { if (stream != null) file.failWrite(stream) }
            main.post {
                if (!success) error = "Edge settings apply for this session but could not be saved."
                done?.invoke(success)
            }
        }
    }
    private fun notifyListeners() { listeners.toList().forEach { it() } }
    companion object {
        private var instance: AccessPreferences? = null
        fun get(context: Context): AccessPreferences {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: AccessPreferences(context.applicationContext).also { instance = it }
        }
    }
}
