package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import org.mozilla.geckoview.GeckoRuntimeSettings

/** User-requested GPU overrides; the engine, profiles, input and fidelity remain unchanged. */
internal object GeckoTurboPolicy {
    @Volatile private var startupConfigPath = ""
    private const val CONFIG_ASSET = "gecko-hardware.yaml"

    /** Called only during explicit Workspace startup, never in Application/subprocess bootstrap. */
    fun prepare(context: Context, done: (String?) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        UploadStaging.io.execute {
            var warning: String? = null
            try {
                val expected = app.assets.open(CONFIG_ASSET).use { it.readBytes() }
                check(expected.size in 1..8192) { "Unexpected hardware policy size" }
                val file = AtomicFile(File(app.noBackupFilesDir, CONFIG_ASSET))
                val unchanged = runCatching {
                    file.openRead().use { input ->
                        val buffer = ByteArray(expected.size + 1)
                        var used = 0
                        while (used < buffer.size) {
                            val count = input.read(buffer, used, buffer.size - used)
                            if (count < 0) break
                            used += count
                        }
                        used == expected.size && buffer.copyOf(used).contentEquals(expected)
                    }
                }.getOrDefault(false)
                if (!unchanged) {
                    val stream = file.startWrite()
                    try { stream.write(expected); file.finishWrite(stream) }
                    catch (failure: Exception) { file.failWrite(stream); throw failure }
                }
                startupConfigPath = file.baseFile.absolutePath
            } catch (_: Exception) {
                // Fail back to the working engine defaults, never use a public config path
                // or sacrifice browsing because configuration storage failed.
                startupConfigPath = ""
                warning = "Hardware overrides could not be prepared; Gecko defaults are in use."
            }
            main.post { done(warning) }
        }
    }

    fun settings(): GeckoRuntimeSettings = GeckoRuntimeSettings.Builder()
        .remoteDebuggingEnabled(false)
        .consoleOutput(false)
        .debugLogging(false)
        .configFilePath(startupConfigPath)
        .build()
}
