package com.mekromn.bubble.browser.engine

import android.content.Context
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.webkit.WebSettings
import com.mekromn.bubble.BuildConfig
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SystemWebViewFactory(
    private val context: Context,
) {
    fun create(tabId: TabId, events: BrowserEngineEvents): BrowserEngineSession {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView sessions must be created on the main thread"
        }
        return SystemWebViewSession(context, tabId, events)
    }
}

private class SystemWebViewSession(
    context: Context,
    override val tabId: TabId,
    private val events: BrowserEngineEvents,
) : BrowserEngineSession {
    private val mutablePageState = MutableStateFlow(EnginePageState())
    override val pageState: StateFlow<EnginePageState> = mutablePageState

    override val webView: WebView = WebView(context).apply {
        configureSettings(settings)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@apply, true)
        }
        webViewClient = createWebViewClient()
        webChromeClient = createChromeClient()
    }

    @Suppress("DEPRECATION")
    private fun configureSettings(settings: WebSettings) = with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            return scheme != "http" && scheme != "https" && scheme != "about"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            publish(
                mutablePageState.value.copy(
                    url = url.orEmpty(),
                    loading = true,
                    progress = 0,
                    favicon = favicon ?: mutablePageState.value.favicon,
                ),
            )
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            publishFromWebView(view, url = url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            publishFromWebView(view, url = url, loading = false, progress = 100)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // A browser may display a certificate error UI, but Bubble never bypasses TLS failures.
            handler.cancel()
            publish(mutablePageState.value.copy(loading = false))
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail,
        ): Boolean {
            events.onRendererGone(tabId, detail.didCrash())
            return true
        }
    }

    private fun createChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            publishFromWebView(view, progress = newProgress, loading = newProgress < 100)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            publishFromWebView(view, title = title)
        }

        override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
            publish(mutablePageState.value.copy(favicon = icon))
        }
    }

    private fun publishFromWebView(
        view: WebView,
        url: String? = view.url,
        title: String? = view.title,
        progress: Int = view.progress,
        loading: Boolean = progress < 100,
    ) {
        publish(
            mutablePageState.value.copy(
                url = url.orEmpty(),
                title = title.orEmpty(),
                progress = progress.coerceIn(0, 100),
                loading = loading,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
            ),
        )
    }

    private fun publish(state: EnginePageState) {
        mutablePageState.value = state
        events.onPageState(tabId, state)
    }

    override fun loadUrl(url: String) = webView.loadUrl(url)

    override fun reload() = webView.reload()

    override fun stop() = webView.stopLoading()

    override fun goBack(): Boolean {
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (!webView.canGoForward()) return false
        webView.goForward()
        return true
    }

    override fun destroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }
}
