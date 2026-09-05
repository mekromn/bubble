package com.mekromn.bubble

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Isolated renderer gate. No old bootstrap, renderer pool, Compose or observer graph. */
class BrowserActivity : Activity() {
    lateinit var geckoView: GeckoView
        private set
    lateinit var selectedSession: GeckoSession
        private set
    var pageTitle = ""
        private set
    var painted = false
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        geckoView = GeckoView(this)
        setContentView(geckoView)
        selectedSession = GeckoSession()
        selectedSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { pageTitle = title.orEmpty() }
            override fun onFirstContentfulPaint(session: GeckoSession) { painted = true }
        }
        if (runtime == null) runtime = GeckoRuntime.create(applicationContext)
        selectedSession.open(runtime!!)
        geckoView.setSession(selectedSession)
        selectedSession.loadUri(intent.dataString ?: "https://www.google.com/")
    }
    override fun onDestroy() {
        geckoView.releaseSession()
        selectedSession.close()
        super.onDestroy()
    }
    companion object { private var runtime: GeckoRuntime? = null }
}
