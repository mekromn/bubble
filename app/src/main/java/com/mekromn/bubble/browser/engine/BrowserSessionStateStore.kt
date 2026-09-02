package com.mekromn.bubble.browser.engine

import android.content.Context
import com.mekromn.bubble.browser.session.TabId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded engine-neutral state cache. GeckoSession.SessionState serializes to a String containing
 * browser history, scroll/zoom and form state; durable URL/title identity remains in Room.
 */
class BrowserSessionStateStore(context: Context) {
    private val root = File(context.filesDir, "browser-session-state-v2")
    @Volatile
    private var totalBudgetBytes: Long = DEFAULT_TOTAL_STATE_BYTES

    suspend fun setTotalBudgetBytes(bytes: Long) {
        totalBudgetBytes = bytes.coerceAtLeast(MAX_STATE_BYTES.toLong())
        pruneToBudget()
    }

    suspend fun save(tabId: TabId, serialized: String?): Boolean {
        if (serialized.isNullOrBlank()) return false
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                root.mkdirs()
                val target = fileFor(tabId)
                val temporary = File(root, "${tabId.value}.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    target.writeBytes(bytes)
                    temporary.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                pruneToBudgetOnIoThread(target)
                true
            }.getOrDefault(false)
        }
    }

    suspend fun restore(tabId: TabId): String? = withContext(Dispatchers.IO) {
        val file = fileFor(tabId).takeIf(File::isFile) ?: return@withContext null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty() || bytes.size > MAX_STATE_BYTES) {
            file.delete()
            return@withContext null
        }
        file.setLastModified(System.currentTimeMillis())
        runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    suspend fun delete(tabId: TabId) {
        withContext(Dispatchers.IO) { fileFor(tabId).delete() }
    }

    private suspend fun pruneToBudget() {
        withContext(Dispatchers.IO) { pruneToBudgetOnIoThread(null) }
    }

    private fun pruneToBudgetOnIoThread(preserve: File?) {
        if (!root.isDirectory) return
        val snapshots = root.listFiles { file -> file.isFile && file.extension == "state" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var total = snapshots.sumOf(File::length)
        snapshots.forEach { candidate ->
            if (total <= totalBudgetBytes) return
            if (candidate == preserve) return@forEach
            val length = candidate.length()
            if (candidate.delete()) total -= length
        }
    }

    private fun fileFor(tabId: TabId): File = File(root, "${tabId.value}.state")

    companion object {
        const val MAX_STATE_BYTES = 1024 * 1024
        const val DEFAULT_TOTAL_STATE_BYTES = 24L * 1024L * 1024L
    }
}
