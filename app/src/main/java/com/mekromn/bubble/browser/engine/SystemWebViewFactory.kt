package com.mekromn.bubble.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.mekromn.bubble.BuildConfig
import com.mekromn.bubble.browser.downloads.SystemDownloadHandler
import com.mekromn.bubble.browser.navigation.ExternalNavigationPolicy
import com.mekromn.bubble.browser.navigation.SystemExternalNavigator
import com.mekromn.bubble.browser.requests.BrowserFileChooserBroker
import com.mekromn.bubble.browser.requests.BrowserPermissionBroker
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SystemWebViewFactory(
    private val context: Context,
) {
    fun create(tab: Tab, events: BrowserEngineEvents): BrowserEngineSession {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView sessions must be created on the main thread"
        }
        return SystemWebViewSession(context, tab, events)
    }
}

private class SystemWebViewSession(
    context: Context,
    tab: Tab,
    private val events: BrowserEngineEvents,
) : BrowserEngineSession {
    private val appContext = context.applicationContext
    override val tabId: TabId = tab.id
    private val mutablePageState = MutableStateFlow(EnginePageState())
    override val pageState: StateFlow<EnginePageState> = mutablePageState
    private val systemUserAgent = WebSettings.getDefaultUserAgent(appContext)
    private val webViewPackageVersion = WebView.getCurrentWebViewPackage()?.versionName
    private val externalNavigator = SystemExternalNavigator(appContext)
    private val downloadHandler = SystemDownloadHandler(appContext)

    override val webView: WebView = WebView(appContext).apply webView@{
        configureSettings(settings)
        applyUserAgent(settings, tab.userAgentMode)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@webView, true)
        }
        webViewClient = createWebViewClient()
        webChromeClient = createChromeClient()
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadHandler.enqueue(
                url = url,
                userAgent = userAgent ?: settings.userAgentString,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                referer = this@webView.url,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureSettings(settings: WebSettings) = with(settings) {
        // JavaScript is a core requirement for a general-purpose modern browser. Bubble does
        // not expose a JavaScript bridge to arbitrary web content, and TLS errors are never
        // bypassed, so the lint warning is reviewed rather than globally disabled.
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        setGeolocationEnabled(true)
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
        safeBrowsingEnabled = true
    }

    override fun setUserAgentMode(mode: UserAgentMode) {
        applyUserAgent(webView.settings, mode)
    }

    private fun applyUserAgent(settings: WebSettings, mode: UserAgentMode) {
        settings.userAgentString = UserAgentPolicy.userAgentString(
            systemUserAgent = systemUserAgent,
            webViewPackageVersion = webViewPackageVersion,
            mode = mode,
        )
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val metadata = when (mode) {
            UserAgentMode.SYSTEM -> UserAgentMetadata.Builder().build()
            UserAgentMode.MOBILE -> chromeMetadata(mobile = true)
            UserAgentMode.DESKTOP -> chromeMetadata(mobile = false)
        }
        WebSettingsCompat.setUserAgentMetadata(settings, metadata)
    }

    private fun chromeMetadata(mobile: Boolean): UserAgentMetadata {
        val version = UserAgentPolicy.chromeVersion(systemUserAgent, webViewPackageVersion)
        val brands = listOf(
            brand("Not_A Brand", "99", "99.0.0.0"),
            brand("Chromium", version.major, version.full),
            brand("Google Chrome", version.major, version.full),
        )
        val builder = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(version.full)
            .setMobile(mobile)
            .setPlatform(if (mobile) "Android" else "Windows")
            .setPlatformVersion(if (mobile) Build.VERSION.RELEASE else "10.0.0")
            .setModel(if (mobile) Build.MODEL else "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(
                listOf(
                    if (mobile) UserAgentMetadata.FORM_FACTOR_MOBILE
                    else UserAgentMetadata.FORM_FACTOR_DESKTOP,
                ),
            )
        }
        return builder.build()
    }

    private fun brand(brand: String, major: String, full: String): UserAgentMetadata.BrandVersion =
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(brand)
            .setMajorVersion(major)
            .setFullVersion(full)
            .build()

    private fun createWebViewClient(): WebViewClientCompat = object : WebViewClientCompat() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val decision = ExternalNavigationPolicy.classify(
                rawUrl = request.url.toString(),
                hasUserGesture = request.hasGesture(),
            )
            return externalNavigator.handle(decision) { fallback -> view.loadUrl(fallback) }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
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

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            publish(mutablePageState.value.copy(favicon = icon))
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = BrowserFileChooserBroker.launch(
            appContext,
            filePathCallback,
            fileChooserParams,
        )

        override fun onPermissionRequest(request: PermissionRequest) {
            BrowserPermissionBroker.requestMedia(appContext, request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            BrowserPermissionBroker.cancel(request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            BrowserPermissionBroker.requestGeolocation(appContext, origin, callback)
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
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        // Keep the crash-aware client attached until destroy(); replacing it with a bare
        // WebViewClient would reintroduce an unhandled renderer-termination path.
        webView.removeAllViews()
        webView.destroy()
    }
}
