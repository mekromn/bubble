package com.mekromn.bubble.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import kotlinx.coroutines.flow.StateFlow

data class BrowserPageError(
    val code: Int,
    val category: Int,
    val failingUrl: String?,
)

data class EnginePageState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val favicon: Bitmap? = null,
    val secure: Boolean? = null,
    val firstContentfulPaint: Boolean = false,
    val error: BrowserPageError? = null,
)

interface BrowserEngineEvents {
    fun onPageState(tabId: TabId, state: EnginePageState)
    fun onRendererGone(tabId: TabId, didCrash: Boolean)
    fun onOpenNewTab(tabId: TabId, url: String)
}

/** Engine-neutral browser-tab contract. */
interface BrowserEngineSession {
    val tabId: TabId
    val contentView: View
    val pageState: StateFlow<EnginePageState>

    fun bindHostContext(context: Context)
    fun releaseHostContext()
    fun setLifecycle(active: Boolean, focused: Boolean, highPriority: Boolean)

    fun loadUrl(url: String)
    fun reload()
    fun stop()
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun setUserAgentMode(mode: UserAgentMode)

    fun serializedState(): String?
    fun restoreSerializedState(serialized: String): Boolean
    fun destroy()
}
