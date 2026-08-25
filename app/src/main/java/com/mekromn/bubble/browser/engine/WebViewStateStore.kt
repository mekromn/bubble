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
                true
            }.getOrDefault(false)
        }
    }

    suspend fun restore(tabId: TabId, webView: WebView): Boolean {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { fileFor(tabId).takeIf(File::isFile)?.readBytes() }.getOrNull()
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
    }

    suspend fun delete(tabId: TabId) {
        withContext(Dispatchers.IO) { fileFor(tabId).delete() }
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
    }
}
