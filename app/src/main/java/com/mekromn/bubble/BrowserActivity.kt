package com.mekromn.bubble

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.concept.engine.EngineSession

/**
 * 0.4.4 hard-isolation browser bring-up.
 *
 * This Activity intentionally does NOT access BubbleApplication.runtime. There is one Mozilla
 * Android Components Gecko engine session and one GeckoEngineView. No Room restore, AI workspace,
 * bubble service, custom RendererPool or Compose renderer participates in browser startup.
 *
 * Once this path is proven on-device, Bubble features can be reattached one subsystem at a time.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var engineHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var omnibox: EditText
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var loadingCover: View
    private lateinit var errorCard: TextView

    private lateinit var engine: GeckoEngine
    private lateinit var engineView: GeckoEngineView
    private lateinit var session: EngineSession

    private var canGoBack = false
    private var canGoForward = false
    private var loading = false
    private var currentUrl = GOOGLE_HOME

    private val observer = object : EngineSession.Observer {
        override fun onLocationChange(url: String, hasUserGesture: Boolean) {
            runOnUiThread {
                currentUrl = url
                if (!omnibox.hasFocus()) omnibox.setText(url)
                errorCard.visibility = View.GONE
            }
        }

        override fun onTitleChange(title: String) {
            runOnUiThread {
                status.text = title.takeIf { it.isNotBlank() } ?: "Android Components Gecko"
            }
        }

        override fun onProgress(progress: Int) {
            runOnUiThread {
                this@BrowserActivity.progress.progress = progress.coerceIn(0, 100)
            }
        }

        override fun onLoadingStateChange(loading: Boolean) {
            runOnUiThread {
                this@BrowserActivity.loading = loading
                progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
                reloadButton.text = if (loading) "×" else "↻"
                if (loading) loadingCover.visibility = View.VISIBLE
            }
        }

        override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
            runOnUiThread {
                canGoBack?.let { this@BrowserActivity.canGoBack = it }
                canGoForward?.let { this@BrowserActivity.canGoForward = it }
                updateNavigationButtons()
            }
        }

        override fun onFirstContentfulPaint() {
            runOnUiThread {
                loadingCover.visibility = View.GONE
                errorCard.visibility = View.GONE
            }
        }

        override fun onCrash() {
            runOnUiThread {
                showEngineError("The Gecko content process crashed. Bubble stayed open so this failure is visible.")
            }
        }

        override fun onProcessKilled() {
            runOnUiThread {
                showEngineError("Android killed the Gecko content process. Bubble stayed open so this failure is visible.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        buildNativeBrowserUi()
        installBackHandling()

        val initialUrl = intentWebUrl(intent) ?: GOOGLE_HOME
        initializeIsolatedEngine(initialUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentWebUrl(intent)?.let(::navigateDirect)
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            runCatching { session.close() }
        }
        if (::engineView.isInitialized) {
            runCatching { engineView.release() }
            runCatching { engineView.setActivityContext(null) }
        }
        super.onDestroy()
    }

    private fun initializeIsolatedEngine(initialUrl: String) {
        runCatching {
            engine = GeckoEngine(applicationContext)
            engine.warmUp()

            session = engine.createSession(false, ISOLATION_CONTEXT_ID)
            session.register(observer)

            engineView = GeckoEngineView(this).apply {
                setActivityContext(this@BrowserActivity)
                setBackgroundColor(BROWSER_BG)
            }
            engineHost.addView(engineView, matchParent())
            engineView.render(session)

            navigateDirect(initialUrl)
        }.onFailure { throwable ->
            showEngineError(
                "Browser engine initialization failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
            )
        }
    }

    private fun buildNativeBrowserUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BROWSER_BG) }
        engineHost = FrameLayout(this).apply { setBackgroundColor(BROWSER_BG) }
        root.addView(engineHost, matchParent())

        loadingCover = View(this).apply {
            setBackgroundColor(BROWSER_BG)
            visibility = View.VISIBLE
        }
        root.addView(loadingCover, matchParent())

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(SURFACE, 28f)
            elevation = dp(10).toFloat()
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.TOP,
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            },
        )

        backButton = chromeButton("‹", "Back") { if (::session.isInitialized && canGoBack) session.goBack() }
        forwardButton = chromeButton("›", "Forward") {
            if (::session.isInitialized && canGoForward) session.goForward()
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(46), dp(46)))
        topBar.addView(forwardButton, LinearLayout.LayoutParams(dp(46), dp(46)))

        omnibox = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_MUTED)
            hint = "Search or enter address"
            textSize = 16f
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(OMNIBOX_BG, 24f)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateInput(text.toString())
                    clearFocus()
                    true
                } else {
                    false
                }
            }
        }
        topBar.addView(
            omnibox,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(6)
            },
        )

        reloadButton = chromeButton("↻", "Reload or stop") {
            if (!::session.isInitialized) return@chromeButton
            if (loading) session.stopLoading() else session.reload()
        }
        topBar.addView(reloadButton, LinearLayout.LayoutParams(dp(46), dp(46)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.INVISIBLE
        }
        root.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            },
        )

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(SURFACE, 28f)
            elevation = dp(10).toFloat()
        }
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62),
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            },
        )

        bottomBar.addView(
            actionPill("Google") { navigateDirect(GOOGLE_HOME) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) },
        )
        bottomBar.addView(
            actionPill("ChatGPT") { navigateDirect(CHATGPT_HOME) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) },
        )
        bottomBar.addView(
            actionPill("New") { navigateDirect(GOOGLE_HOME) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) },
        )

        status = TextView(this).apply {
            setTextColor(TEXT_MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            text = getString(R.string.browser_isolation_status)
            maxLines = 1
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20), Gravity.BOTTOM),
        )

        errorCard = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(ERROR_BG, 18f)
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        root.addView(
            errorCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = dp(16)
                rightMargin = dp(16)
            },
        )

        setContentView(root)
        applyWindowInsets()
        updateNavigationButtons()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )

            topBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = safe.top + dp(6)
            }
            progress.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = safe.top + dp(68)
            }
            bottomBar.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = safe.bottom + dp(6)
            }
            status.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = safe.bottom + dp(70)
            }
            errorCard.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = safe.top + dp(76)
            }
            engineHost.setPadding(
                safe.left,
                safe.top + dp(76),
                safe.right,
                safe.bottom + dp(78),
            )
            loadingCover.setPadding(
                safe.left,
                safe.top + dp(76),
                safe.right,
                safe.bottom + dp(78),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::session.isInitialized && canGoBack) {
                        session.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun navigateInput(raw: String) {
        val input = raw.trim()
        if (input.isBlank()) return
        val resolved = when {
            input.startsWith("https://", true) || input.startsWith("http://", true) -> input
            DOMAIN_LIKE.matches(input) -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        navigateDirect(resolved)
    }

    private fun navigateDirect(url: String) {
        if (!::session.isInitialized) return
        currentUrl = url
        omnibox.setText(url)
        loadingCover.visibility = View.VISIBLE
        errorCard.visibility = View.GONE
        session.loadUrl(url)
    }

    private fun showEngineError(message: String) {
        loadingCover.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        errorCard.text = message
        errorCard.visibility = View.VISIBLE
        status.text = getString(R.string.browser_engine_failure_status)
    }

    private fun updateNavigationButtons() {
        backButton.isEnabled = canGoBack
        backButton.alpha = if (canGoBack) 1f else 0.35f
        forwardButton.isEnabled = canGoForward
        forwardButton.alpha = if (canGoForward) 1f else 0.35f
    }

    private fun chromeButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            setTextColor(Color.WHITE)
            textSize = 27f
            gravity = Gravity.CENTER
            background = rounded(Color.TRANSPARENT, 22f)
            isClickable = true
            isFocusable = true
            setOnClickListener { animateTap(this); action() }
        }

    private fun actionPill(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(PILL_BG, 22f)
            isClickable = true
            isFocusable = true
            setOnClickListener { animateTap(this); action() }
        }

    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(55)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(95).start()
            }
            .start()
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun matchParent(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun intentWebUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.takeIf { value ->
            value.startsWith("https://", true) || value.startsWith("http://", true)
        }
    }

    companion object {
        private const val GOOGLE_HOME = "https://www.google.com/"
        private const val CHATGPT_HOME = "https://chatgpt.com/"
        private const val ISOLATION_CONTEXT_ID = "bubble-isolation-v1"

        private val DOMAIN_LIKE = Regex(
            "^(localhost|(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}|(?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d+)?(?:/.*)?$",
        )

        private val BROWSER_BG = Color.rgb(8, 10, 14)
        private val SURFACE = Color.rgb(25, 28, 36)
        private val OMNIBOX_BG = Color.rgb(35, 39, 49)
        private val PILL_BG = Color.rgb(43, 49, 62)
        private val ERROR_BG = Color.rgb(105, 30, 38)
        private val TEXT_MUTED = Color.rgb(170, 176, 190)

        const val EXTRA_RESTORE_TAB_ID = "com.mekromn.bubble.extra.RESTORE_TAB_ID"
        const val EXTRA_AI_REPLY_GENERATION = "com.mekromn.bubble.extra.AI_REPLY_GENERATION"
    }
}
