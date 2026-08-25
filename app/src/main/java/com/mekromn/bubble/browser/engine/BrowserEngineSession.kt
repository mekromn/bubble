package com.mekromn.bubble.browser.engine

import android.graphics.Bitmap
import android.webkit.WebView
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.flow.StateFlow

data class EnginePageState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val favicon: Bitmap? = null,
)

interface BrowserEngineEvents {
    fun onPageState(tabId: TabId, state: EnginePageState)
    fun onRendererGone(tabId: TabId, didCrash: Boolean)
}

interface BrowserEngineSession {
    val tabId: TabId
    val webView: WebView
    val pageState: StateFlow<EnginePageState>

    fun loadUrl(url: String)
    fun reload()
    fun stop()
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun destroy()
}
