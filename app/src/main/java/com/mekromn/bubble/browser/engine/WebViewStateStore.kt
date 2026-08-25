package com.mekromn.bubble.browser.engine

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mekromn.bubble.browser.session.TabId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebViewStateStore(context: Context) {
    private val root = File(context.filesDir, "webview-state")
    @Volatile
    private var totalBudgetBytes: Long = DEFAULT_TOTAL_STATE_BYTES

    suspend fun setTotalBudgetBytes(bytes: Long) {
        totalBudgetBytes = bytes.coerceAtLeast(MAX_STATE_BYTES.toLong())
        pruneToBudget()
    }

    suspend fun save(tabId: TabId, webView: WebView): Boolean {
        val bundle = Bundle()
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
                WebViewCompat.saveState(
                    webView,
                    bundle,
                    MAX_STATE_BYTES,
                    INCLUDE_FORWARD_HISTORY,
                )
            } else {
                webView.saveState(bundle)
            }
        }.getOrElse { return false }

        val bytes = bundleToBytes(bundle)
        if (bytes.isEmpty() || bytes.size > MAX_STATE_BYTES) return false

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
                pruneToBudgetOnIoThread(preserve = target)
                true
            }.getOrDefault(false)
        }
    }

    suspend fun restore(tabId: TabId, webView: WebView): Boolean {
        val file = fileFor(tabId)
        val bytes = withContext(Dispatchers.IO) {
            runCatching { file.takeIf(File::isFile)?.readBytes() }.getOrNull()
        } ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_STATE_BYTES) {
            delete(tabId)
            return false
        }
        val bundle = bytesToBundle(bytes) ?: run {
            delete(tabId)
            return false
        }
        return runCatching { webView.restoreState(bundle) != null }
            .getOrElse {
                delete(tabId)
                false
            }
            .also { restored ->
                if (restored) {
                    withContext(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) }
                }
            }
    }

    suspend fun delete(tabId: TabId) {
        withContext(Dispatchers.IO) { fileFor(tabId).delete() }
    }

    private suspend fun pruneToBudget() {
        withContext(Dispatchers.IO) { pruneToBudgetOnIoThread(preserve = null) }
    }

    private fun pruneToBudgetOnIoThread(preserve: File?) {
        if (!root.isDirectory) return
        val snapshots = root.listFiles { file -> file.isFile && file.extension == "bin" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var total = snapshots.sumOf(File::length)
        if (total <= totalBudgetBytes) return

        snapshots.forEach { candidate ->
            if (total <= totalBudgetBytes) return
            if (preserve != null && candidate == preserve) return@forEach
            val size = candidate.length()
            if (candidate.delete()) total -= size
        }
    }

    private fun fileFor(tabId: TabId): File = File(root, "${tabId.value}.bin")

    private fun bundleToBytes(bundle: Bundle): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun bytesToBundle(bytes: ByteArray): Bundle? {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(WebView::class.java.classLoader)
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        const val MAX_STATE_BYTES = 512 * 1024
        const val INCLUDE_FORWARD_HISTORY = true
        const val DEFAULT_TOTAL_STATE_BYTES = 16L * 1024L * 1024L
    }
}
