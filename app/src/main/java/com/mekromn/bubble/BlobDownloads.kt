package com.mekromn.bubble

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError

/** Page-created Blob URLs are process/runtime objects, not network URLs. GeckoView does not
 * reliably route an <a download=... href=blob:...> response through ContentDelegate, so the
 * isolated WebExtension bridge forwards only the Blob URL + display metadata. The bytes never
 * cross JavaScript/native messaging: GeckoWebExecutor resolves the Blob in the same runtime and
 * BrowserDownloads consumes the returned WebResponse stream exactly once. */
internal object BlobDownloads {
    private val main = Handler(Looper.getMainLooper())

    /** Ephemeral in-process diagnostic only. No file/network telemetry and no URL/content data. */
    @Volatile internal var diagnosticStage = "idle"
        private set

    internal fun resetDiagnostic() { diagnosticStage = "idle" }

    fun receive(app: android.content.Context, workspace: Workspace, tab: ChatTab,
        session: GeckoSession, runtime: GeckoRuntime, senderUrl: String, message: JSONObject): Boolean {
        diagnosticStage = "message"
        val blob = message.optString("uri").takeIf { it.length in 6..16384 && it.startsWith("blob:") }
            ?: run { diagnosticStage = "rejected-uri"; return false }
        if (!sameOrigin(blob, senderUrl) || !Policy.isWeb(senderUrl)) {
            diagnosticStage = "rejected-origin"; return false
        }
        val name = FileNames.safe(message.optString("filename").take(512), "download")
        val mime = message.optString("mime").take(128).takeIf { it.contains('/') }
        // GeckoWebExecutor explicitly supports blob: URLs. Preserve the creating document as
        // referrer so Gecko can retain the source context instead of treating this as an unrelated
        // system fetch. The Blob bytes still never pass through WebExtension/native messaging.
        val request = runCatching { WebRequest.Builder(blob).referrer(senderUrl).build() }.getOrNull()
            ?: run { diagnosticStage = "request-error"; return false }
        diagnosticStage = "fetch-started"

        GeckoWebExecutor(runtime).fetch(request).accept({ response ->
            if (response == null) {
                diagnosticStage = "fetch-null"
                main.post { failed(workspace, tab, session) }
            } else {
                diagnosticStage = "fetch-response"
                main.post {
                    if (tab !in workspace.tabs || tab.session !== session || !session.isOpen) {
                        diagnosticStage = "discarded"
                        Thread({ runCatching { response.body?.close() } }, "bubble-discard-blob").start()
                        return@post
                    }
                    BrowserDownloads.receive(app, tab.profileId, response,
                        workspace.chatVisible && tab.id == workspace.selectedId, name, mime)
                    diagnosticStage = "downloader"
                }
            }
        }, { error ->
            diagnosticStage = when (error) {
                is WebRequestError -> "fetch-error-${error.category}-${error.code}"
                else -> "fetch-error-${error.javaClass.simpleName.take(48)}"
            }
            main.post { failed(workspace, tab, session) }
        })
        return true
    }

    private fun failed(workspace: Workspace, tab: ChatTab, session: GeckoSession) {
        if (tab in workspace.tabs && tab.session === session) {
            workspace.notice = "That generated file could not be opened. Try the download again."
            workspace.changed()
        }
    }

    private fun sameOrigin(blob: String, page: String): Boolean = runCatching {
        val inner = Uri.parse(blob.removePrefix("blob:"))
        val source = Uri.parse(page)
        fun port(uri: Uri): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            else -> 80
        }
        inner.scheme in setOf("http", "https") && inner.scheme == source.scheme &&
            inner.host != null && inner.host == source.host && port(inner) == port(source)
    }.getOrDefault(false)
}
