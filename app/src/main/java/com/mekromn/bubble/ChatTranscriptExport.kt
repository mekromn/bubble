package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

internal data class ChatTranscriptExport(
    val transfer: String,
    val sourceId: String,
    val filename: String,
    val file: File,
    val bytes: Long
)

/**
 * Builds a complete Markdown transcript from the native Continuity Vault and exposes it to the
 * exact owning GeckoSession in bounded chunks. The page never receives a native filesystem path.
 * Temporary exports live only in app-private no-backup storage and are deleted after completion,
 * cancellation, or process restart.
 */
internal object ChatTranscriptExports {
    private const val MAX_CHUNK_BYTES = 32 * 1024
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Bubble-chat-transcript-export").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            io.execute { root(context).deleteRecursively(); root(context).mkdirs() }
        }
    }

    fun begin(context: Context, vault: ChatVault, url: String, profileId: String,
        callback: (ChatTranscriptExport?) -> Unit) {
        initialize(context)
        if (!Policy.isChat(url) || profileId.isBlank()) { callback(null); return }
        val routeId = ROUTE.find(url)?.groupValues?.getOrNull(1)
        val summaryId = match(vault.summaries(), url, profileId)?.id
        val id = routeId ?: summaryId
        if (id == null) { callback(null); return }

        // vault.read() is serialized on ChatVault's IO lane. If the page flushed its newest rendered
        // snapshot immediately before requesting this export, that queued commit is guaranteed to
        // complete before this read runs, so the transcript includes the request turn itself.
        vault.read(id) { direct ->
            if (direct != null && direct.profileId == profileId) {
                build(context, direct, callback)
                return@read
            }
            val fallback = match(vault.summaries(), url, profileId)
            if (fallback == null || fallback.id == id) { callback(null); return@read }
            vault.read(fallback.id) { chat ->
                if (chat == null || chat.profileId != profileId) callback(null)
                else build(context, chat, callback)
            }
        }
    }

    /** Export one explicitly staged source chat. Used by fresh-chat continuity attachment handoff. */
    fun beginById(context: Context, vault: ChatVault, sourceId: String, profileId: String,
        callback: (ChatTranscriptExport?) -> Unit) {
        initialize(context)
        if (sourceId.isBlank() || profileId.isBlank()) { callback(null); return }
        vault.read(sourceId) { chat ->
            if (chat == null || chat.profileId != profileId) callback(null)
            else build(context, chat, callback)
        }
    }

    private fun build(context: Context, chat: VaultChat, callback: (ChatTranscriptExport?) -> Unit) {
        io.execute {
            val transfer = UUID.randomUUID().toString()
            val dir = File(root(context), transfer).apply { mkdirs() }
            val filename = transcriptName(chat.title)
            val file = File(dir, filename)
            val success = runCatching {
                file.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { out ->
                    out.appendLine("# ChatGPT conversation transcript")
                    out.appendLine()
                    out.appendLine("- Title: ${chat.title.replace('\n', ' ')}")
                    out.appendLine("- Conversation: ${chat.url}")
                    out.appendLine("- Messages: ${chat.messages.size}")
                    out.appendLine()
                    chat.messages.forEachIndexed { index, message ->
                        out.append("## ").append((index + 1).toString()).append(" · ")
                            .append(if (message.role == "user") "USER" else "ASSISTANT").appendLine()
                        out.appendLine()
                        out.appendLine(message.text)
                        out.appendLine()
                    }
                }
                file.isFile && file.length() > 0
            }.getOrDefault(false)
            val result = if (success) ChatTranscriptExport(transfer, chat.id, filename, file, file.length()) else null
            if (!success) dir.deleteRecursively()
            main.post { callback(result) }
        }
    }

    fun chunk(export: ChatTranscriptExport, offset: Long, requested: Int,
        callback: (JSONObjectChunk?) -> Unit) {
        if (offset < 0 || offset > export.bytes) { callback(null); return }
        val size = requested.coerceIn(1, MAX_CHUNK_BYTES)
        io.execute {
            val result = runCatching {
                RandomAccessFile(export.file, "r").use { input ->
                    input.seek(offset)
                    val remaining = (export.bytes - offset).coerceAtLeast(0)
                    val count = minOf(size.toLong(), remaining).toInt()
                    val bytes = ByteArray(count)
                    if (count > 0) input.readFully(bytes)
                    val next = offset + count
                    JSONObjectChunk(
                        Base64.encodeToString(bytes, Base64.NO_WRAP),
                        next,
                        next >= export.bytes
                    )
                }
            }.getOrNull()
            main.post { callback(result) }
        }
    }

    fun discard(export: ChatTranscriptExport) {
        io.execute { export.file.parentFile?.deleteRecursively() }
    }

    internal data class JSONObjectChunk(val base64: String, val next: Long, val done: Boolean)

    private fun match(items: List<VaultSummary>, url: String, profileId: String): VaultSummary? {
        val route = ROUTE.find(url)?.groupValues?.getOrNull(1)
        return items.firstOrNull { item ->
            item.profileId == profileId &&
                (item.url == url || (route != null && (item.id == route || item.url.contains("/c/$route"))))
        }
    }

    private fun transcriptName(title: String): String {
        val base = FileNames.safe(title.ifBlank { "ChatGPT" }, "ChatGPT").removeSuffix(".md")
        return FileNames.safe("$base-transcript.md", "ChatGPT-transcript.md")
    }

    private fun root(context: Context) = File(context.noBackupFilesDir, "chat-transcript-exports-v1")
    private val ROUTE = Regex("/c/([^/?#]+)")
}
