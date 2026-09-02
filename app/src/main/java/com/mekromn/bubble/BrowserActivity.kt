package com.mekromn.bubble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.bubble.browser.navigation.SharedUrlExtractor
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.ui.browser.BrowserScreenV2
import com.mekromn.bubble.ui.browser.BrowserViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * BrowserActivity owns two independent layers:
 * 1) a native GeckoView content host attached directly to the Activity/window, and
 * 2) a transparent Compose chrome layer above it.
 *
 * Gecko therefore follows Mozilla's normal View integration and never depends on AndroidView.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var browserHost: FrameLayout
    private lateinit var chromeLayer: BrowserChromeLayer
    private var notificationPermissionRequestedThisActivity = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) (application as? BubbleApplication)?.runtime?.replyNotifications?.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installBrowserLayers()
        observeRendererProjection()
        observeAiWorkspaceState()
        if (savedInstanceState == null) dispatchIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val app = application as BubbleApplication
        app.runtime.rendererPool.attachHost(this)
        app.runtime.setBrowserForeground(true)
        lifecycleScope.launch {
            app.runtime.sessions.initialize()
            app.runtime.sessions.state.value.selectedTabId?.let { tabId ->
                app.runtime.aiWorkspaces.workspaceForTab(tabId)?.let { workspace ->
                    app.runtime.aiWorkspaces.setCollapsed(workspace.id, false)
                }
                app.runtime.aiWorkspaces.markRead(tabId)
            }
        }
    }

    override fun onStop() {
        val app = application as BubbleApplication
        app.runtime.setBrowserForeground(false)
        app.runtime.rendererPool.detachHost(this)
        browserHost.removeAllViews()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingIntent(intent)
    }

    private fun installBrowserLayers() {
        browserHost = FrameLayout(this).apply {
            setBackgroundColor(BROWSER_BACKGROUND)
            clipChildren = true
            clipToPadding = true
        }

        chromeLayer = BrowserChromeLayer(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            composeView.setBackgroundColor(Color.TRANSPARENT)
            composeView.setContent {
                BubbleTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                    ) {
                        val browserViewModel: BrowserViewModel = viewModel()
                        BrowserScreenV2(
                            viewModel = browserViewModel,
                            onModalInteractionChanged = { blocksPage ->
                                blockAllPageTouches = blocksPage
                            },
                        )
                    }
                }
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(BROWSER_BACKGROUND)
            addView(browserHost, matchParentParams())
            addView(chromeLayer, matchParentParams())
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val topChrome = dp(TOP_CHROME_DP)
            val bottomChrome = dp(BOTTOM_CHROME_DP)
            browserHost.setPadding(
                safe.left,
                safe.top + topChrome,
                safe.right,
                safe.bottom + bottomChrome,
            )
            chromeLayer.topInteractivePx = (safe.top + topChrome).toFloat()
            chromeLayer.bottomInteractivePx = (safe.bottom + bottomChrome).toFloat()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun observeRendererProjection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as BubbleApplication).runtime.rendererPool.activeWebView.collect { contentView ->
                    if (
                        contentView != null &&
                        browserHost.childCount == 1 &&
                        browserHost.getChildAt(0) === contentView
                    ) {
                        return@collect
                    }

                    browserHost.removeAllViews()
                    if (contentView != null) {
                        (contentView.parent as? ViewGroup)?.removeView(contentView)
                        browserHost.addView(contentView, matchParentParams())
                    }
                }
            }
        }
    }

    private fun dispatchIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        lifecycleScope.launch {
            val app = application as BubbleApplication
            val sessions = app.runtime.sessions
            sessions.initialize()

            incoming.getStringExtra(EXTRA_RESTORE_TAB_ID)?.let { rawId ->
                runCatching {
                    val tabId = TabId(rawId)
                    app.runtime.aiWorkspaces.workspaceForTab(tabId)?.let { workspace ->
                        app.runtime.aiWorkspaces.setCollapsed(workspace.id, false)
                    }
                    sessions.activate(tabId)
                    app.runtime.aiWorkspaces.markRead(tabId)
                }
                return@launch
            }

            val url = when (incoming.action) {
                Intent.ACTION_VIEW -> incoming.dataString?.takeIf { value ->
                    val scheme = runCatching { value.toUri().scheme }.getOrNull()
                    scheme.equals("http", true) || scheme.equals("https", true)
                }
                Intent.ACTION_SEND -> SharedUrlExtractor.extract(
                    incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
                )
                else -> null
            }
            if (url != null) sessions.createTab(url)
        }
    }

    private fun observeAiWorkspaceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as BubbleApplication).runtime.aiWorkspaces.state
                    .map { state -> state.workspaces.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { hasWorkspace ->
                        if (hasWorkspace) requestNotificationPermissionIfNeeded()
                    }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequestedThisActivity) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            (application as BubbleApplication).runtime.replyNotifications.refresh()
            return
        }
        notificationPermissionRequestedThisActivity = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun matchParentParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * A normal ViewGroup handles hit routing; ComposeView remains an unmodified final AndroidX
     * class. During normal browsing the center returns false on ACTION_DOWN, allowing FrameLayout
     * to dispatch the gesture to GeckoView underneath. Browser chrome and modal overlays remain
     * fully interactive.
     */
    private class BrowserChromeLayer(context: Context) : FrameLayout(context) {
        val composeView = ComposeView(context)
        var blockAllPageTouches: Boolean = false
        var topInteractivePx: Float = 0f
        var bottomInteractivePx: Float = 0f

        init {
            addView(
                composeView,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (blockAllPageTouches) {
                super.dispatchTouchEvent(event)
                return true
            }

            val inTopChrome = event.y <= topInteractivePx
            val inBottomChrome = event.y >= height - bottomInteractivePx
            if (!inTopChrome && !inBottomChrome) return false
            return super.dispatchTouchEvent(event)
        }
    }

    companion object {
        private val BROWSER_BACKGROUND = Color.rgb(9, 11, 15)
        private const val TOP_CHROME_DP = 70
        private const val BOTTOM_CHROME_DP = 76

        const val EXTRA_RESTORE_TAB_ID = "com.mekromn.bubble.extra.RESTORE_TAB_ID"
        const val EXTRA_AI_REPLY_GENERATION = "com.mekromn.bubble.extra.AI_REPLY_GENERATION"
    }
}
