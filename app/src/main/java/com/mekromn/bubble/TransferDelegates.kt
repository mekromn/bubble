package com.mekromn.bubble

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/** Native file handling does not widen URL, TLS or permission policies. */
internal object TransferDelegates {
    fun install(app: Context, ws: Workspace, tab: ChatTab, session: GeckoSession) {
        val original = session.contentDelegate ?: return
        // Do not use `ContentDelegate by original`: Kotlin does not forward inherited
        // Java default methods. Gecko defines its callbacks as default methods, so that
        // seemingly harmless wrapper swallowed title, paint and close events.
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) = original.onTitleChange(s, title)
            override fun onFirstContentfulPaint(s: GeckoSession) = original.onFirstContentfulPaint(s)
            override fun onCloseRequest(s: GeckoSession) = original.onCloseRequest(s)
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                if (tab !in ws.tabs || tab.session !== s) {
                    Thread({ runCatching { response.body?.close() } }, "bubble-discard-response").start()
                    return
                }
                tab.cancelledLoad = true; tab.loading = false; tab.error = null; tab.url = tab.documentUrl
                BrowserDownloads.receive(app, tab.profileId, response, ws.chatVisible && tab.id == ws.selectedId)
                ws.changed(true)
            }
            override fun onCrash(s: GeckoSession) { FloatingFileActivity.cancelForSession(s); original.onCrash(s) }
            override fun onKill(s: GeckoSession) { FloatingFileActivity.cancelForSession(s); original.onKill(s) }
        }
    }
}
