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

/**
 * Engine-neutral browser-tab contract.
 *
 * The durable browser session owns navigation/process state, not its Android View. A foreground
 * host asks the session for a View constructed with the real Activity context and releases that
 * View when the host goes away. This is especially important for GeckoView: GeckoSession may
 * stay alive without a view, while GeckoView itself should be born inside the Activity/window
 * that will display its compositor surface.
 */
interface BrowserEngineSession {
    val tabId: TabId
    val pageState: StateFlow<EnginePageState>

    fun createContentView(context: Context): View
    fun releaseContentView()
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
