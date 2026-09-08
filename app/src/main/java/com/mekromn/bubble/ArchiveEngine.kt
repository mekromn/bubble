package com.mekromn.bubble

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class ArchiveCompression(val label: String, val level: Int) {
    STORE("Store", Deflater.NO_COMPRESSION),
    FAST("Fast", Deflater.BEST_SPEED),
    BALANCED("Balanced", Deflater.DEFAULT_COMPRESSION),
    MAXIMUM("Maximum", Deflater.BEST_COMPRESSION)
}

internal data class ArchiveSource(
    val uri: Uri,
    val displayName: String,
    val size: Long = -1L,
    val relativePath: String? = null
)

internal data class ArchiveProgress(
    val fileIndex: Int,
    val fileCount: Int,
    val currentName: String,
    val bytesProcessed: Long,
    val totalBytes: Long
)

internal class ArchiveJob {
    val cancelled = AtomicBoolean(false)
    @Volatile var source: InputStream? = null
    fun cancel() {
        cancelled.set(true)
        source?.let { input -> Thread { runCatching { input.close() } }.start() }
    }
}

/** Streaming ZIP pipeline. Source files are never copied to a temporary directory first. */
internal object ArchiveEngine {
    fun defaultName(now: Long = System.currentTimeMillis()): String =
        "Documents_${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(now))}.zip"

    fun source(context: Context, uri: Uri, relativePath: String? = null): ArchiveSource {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty())
            name = file.name
            size = file.length()
        } else {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = cursor.getString(ni)
                        if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si)
                    }
                }
            }
        }
        return ArchiveSource(uri, FileNames.safe(name, "attachment"), size, relativePath)
    }

    fun createZip(
        context: Context,
        sources: List<ArchiveSource>,
        output: File,
        compression: ArchiveCompression,
        preservePaths: Boolean,
        job: ArchiveJob,
        progress: (ArchiveProgress) -> Unit = {}
    ): File {
        require(sources.isNotEmpty()) { "No archive sources" }
        output.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val total = sources.map { it.size.coerceAtLeast(0L) }.sum()
        var processed = 0L
        val usedNames = HashSet<String>()
        try {
            ZipOutputStream(BufferedOutputStream(output.outputStream(), 128 * 1024)).use { zip ->
                zip.setLevel(compression.level)
                val buffer = ByteArray(128 * 1024)
                sources.forEachIndexed { index, item ->
                    if (job.cancelled.get()) throw IOException("Archive cancelled")
                    val requested = if (preservePaths) {
                        item.relativePath?.trim('/')?.takeIf { it.isNotBlank() }?.let { "$it/${item.displayName}" } ?: item.displayName
                    } else item.displayName
                    val entryName = uniqueEntryName(sanitizeEntryPath(requested), usedNames)
                    val entry = ZipEntry(entryName)
                    zip.putNextEntry(entry)
                    val input = open(context, item.uri)
                    job.source = input
                    input.use { raw ->
                        val source = BufferedInputStream(raw, 128 * 1024)
                        while (true) {
                            if (job.cancelled.get()) throw IOException("Archive cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            zip.write(buffer, 0, count)
                            processed += count
                            progress(ArchiveProgress(index + 1, sources.size, item.displayName, processed, total))
                        }
                    }
                    job.source = null
                    zip.closeEntry()
                    progress(ArchiveProgress(index + 1, sources.size, item.displayName, processed, total))
                }
                zip.finish()
            }
            if (job.cancelled.get()) throw IOException("Archive cancelled")
            return output
        } catch (t: Throwable) {
            job.source = null
            output.delete()
            throw t
        }
    }

    private fun open(context: Context, uri: Uri): InputStream = when (uri.scheme) {
        "file" -> FileInputStream(File(uri.path ?: throw IOException("Missing file path")))
        "content" -> context.contentResolver.openInputStream(uri) ?: throw IOException("Provider returned no file")
        else -> throw IOException("Unsupported attachment URI")
    }

    private fun sanitizeEntryPath(raw: String): String {
        val parts = raw.replace('\\', '/').split('/').mapNotNull { part ->
            FileNames.safe(part, "").takeIf { it.isNotBlank() && it != "." && it != ".." }
        }
        return parts.joinToString("/").ifBlank { "attachment" }
    }

    private fun uniqueEntryName(requested: String, used: MutableSet<String>): String {
        if (used.add(requested.lowercase(Locale.ROOT))) return requested
        val slash = requested.lastIndexOf('/')
        val dir = if (slash >= 0) requested.substring(0, slash + 1) else ""
        val leaf = if (slash >= 0) requested.substring(slash + 1) else requested
        val dot = leaf.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot >= 0) leaf.substring(0, dot) else leaf
        val ext = if (dot >= 0) leaf.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$dir$stem ($n)$ext"
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            n++
        }
    }
}

/** Ephemeral externally-shareable archives. Internal ChatGPT uploads use UploadStaging instead. */
internal object ArchiveCache {
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    fun root(context: Context) = File(context.cacheDir, "archive-picker")
    fun output(context: Context, requested: String): File {
        val root = root(context).apply { mkdirs() }
        var name = FileNames.safe(requested, ArchiveEngine.defaultName())
        if (!name.endsWith(".zip", true)) name += ".zip"
        var file = File(root, name)
        var n = 2
        while (file.exists()) {
            val base = name.removeSuffix(".zip")
            file = File(root, "$base ($n).zip")
            n++
        }
        return file
    }
    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        root(context).listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.deleteRecursively() }
    }
}
