package com.mekromn.bubble

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/** Native file handling does not widen URL, TLS or permission policies. */
internal object TransferDelegates {
    fun install(app: Context, ws: Workspace, tab: ChatTab, session: GeckoSession) {
        val original = session.contentDelegate ?: return
        session.contentDelegate = object : GeckoSession.ContentDelegate by original {
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                if (tab !in ws.tabs || tab.session !== s) { Thread { runCatching { response.body?.close() } }.start(); return }
                tab.cancelledLoad = true; tab.loading = false; tab.error = null; tab.url = tab.documentUrl
                BrowserDownloads.receive(app, tab.profileId, response, ws.chatVisible && tab.id == ws.selectedId)
                ws.changed(true)
            }
            override fun onCrash(s: GeckoSession) { FloatingFileActivity.cancelForSession(s); original.onCrash(s) }
            override fun onKill(s: GeckoSession) { FloatingFileActivity.cancelForSession(s); original.onKill(s) }
        }
    }
}
