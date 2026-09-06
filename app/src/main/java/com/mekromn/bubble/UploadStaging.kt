package com.mekromn.bubble

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Gecko FilePrompt needs readable local files, not a provider URI or its legacy _data path.
 * Only explicitly selected bytes are copied, unmodified, on one IO lane. No whole-file buffers.
 * Files live outside evictable cache while their session may still use them (including BFCache).
 */
internal object UploadStaging {
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-attachment-IO").apply { isDaemon = true } }
    private var initialized = false
    class Job {
        val cancelled = AtomicBoolean(false)
        @Volatile var source: InputStream? = null
        fun cancel() { cancelled.set(true); source?.let { stream -> Thread { runCatching { stream.close() } }.start() } }
    }
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // After process death no surviving Gecko File references can use the old staging tree.
        io.execute { root(context).deleteRecursively() }
    }
    fun release(context: Context, tabId: String) {
        io.execute { File(root(context), UUID.fromString(tabId).toString()).deleteRecursively() }
    }
    private fun root(context: Context) = File(context.noBackupFilesDir, "attachment-staging-v1")
    fun prepare(context: Context, tabId: String, id: String, selected: List<Uri>, job: Job): List<Uri> {
        val base = File(File(root(context), UUID.fromString(tabId).toString()), UUID.fromString(id).toString())
        try {
            check(base.mkdirs() || base.isDirectory)
            val result = ArrayList<Uri>()
            for ((index, uri) in selected.withIndex()) {
                if (job.cancelled.get()) throw IOException("Attachment selection cancelled")
                require(uri.scheme == "content") { "Only user-selected content is accepted" }
                var displayName: String? = null
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) displayName = cursor.getString(0)
                    }
                }
                var name = FileNames.safe(displayName, "attachment")
                if (!name.contains('.')) {
                    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    if (!ext.isNullOrBlank()) name += ".$ext"
                }
                // Separate directories preserve duplicate display names without renaming user files.
                val dir = File(base, index.toString()).apply { check(mkdir()) }
                val file = File(dir, name)
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Provider returned no file")
                job.source = input
                input.use { source -> file.outputStream().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (job.cancelled.get()) throw IOException("Attachment selection cancelled")
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count > 0) destination.write(buffer, 0, count)
                    }
                    destination.fd.sync()
                } }
                job.source = null
                result += Uri.fromFile(file)
            }
            if (job.cancelled.get()) throw IOException("Attachment selection cancelled")
            return result
        } catch (e: Exception) { job.source = null; base.deleteRecursively(); throw e }
    }
    fun discard(context: Context, tabId: String, id: String) {
        io.execute { File(File(root(context), UUID.fromString(tabId).toString()), UUID.fromString(id).toString()).deleteRecursively() }
    }
}
