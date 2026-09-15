package com.mekromn.bubble

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs
import kotlin.math.min

/**
 * Build 161: the expanded floating browser IS the Activity window.
 *
 * There is no helper/anchor Activity and no service-owned expanded browser overlay. The same window
 * that puts Bubble in Android's TOP/RESUMED scheduling class owns the exact single-ViewRoot chrome
 * and Gecko SurfaceView. Its PhoneWindow is elevated to TYPE_APPLICATION_OVERLAY so the expanded
 * browser can remain in the overlay layer while retaining Activity lifecycle/scheduling semantics.
 *
 * This is intentionally a physical-device experiment. Android can still impose lifecycle/window
 * policy when another task is launched; compilation cannot prove persistence or scheduling parity.
 */
class FloatingBrowserActivity : Activity() {
    private lateinit var service: BubbleService
    private lateinit var workspace: Workspace
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private val motion = WindowMotion()
    private val dismiss by lazy { DismissTarget(this) }
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private var destroyed = false
    private var frameQueued = false
    private var hiding = false
    private var imeBottom = 0
    private var ignoreOutsideUntil = 0L
    private var rectangle = WindowBox(0, 0, 1, 1)
    private var target = rectangle
    private var chatBox: WindowBox? = null
    private var chooserBox: WindowBox? = null
    private var gestureInitial = rectangle
    private var gestureX = 0f
    private var gestureY = 0f
    private var dragging = false
    private var glassBlur = false

    private var list: ConversationList? = null
    private var heading: TextView? = null
    private var subtitle: TextView? = null
    private var error: TextView? = null
    private var count: GlyphView? = null
    private var backControl: GlyphView? = null
    private var geckoWindow: FloatingPageHost? = null
    private var pageContainer: FrameLayout? = null
    private val gecko: LiveGeckoView? get() = geckoWindow?.view

    private var mode = FloatingMode.CHAT
    private val listener: () -> Unit = { render() }

    internal val geckoView: GeckoView? get() = gecko
    internal val pageHost: FloatingPageHost? get() = geckoWindow
    internal val transitionView: View get() = root
    internal val box: WindowBox get() = rectangle
    internal val currentMode: FloatingMode get() = mode

    private val root = object : FrameLayout(this) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            post { if (!destroyed && ::workspace.isInitialized) workspace.applyPolicy() }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE && mode == FloatingMode.CHOOSER &&
                SystemClock.uptimeMillis() >= ignoreOutsideUntil && !workspace.quickMenuVisible) {
                minimize()
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        service = BubbleService.active ?: run {
            finishAndRemoveTask()
            return
        }
        workspace = Workspace.get(this)
        if (!workspace.ready) {
            finishAndRemoveTask()
            return
        }

        // The Activity's own PhoneWindow is the floating browser window. No second interactive
        // WindowManager root is created for expanded mode.
        runCatching { window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
            .onFailure {
                service.activityHostFailed(this, "Android rejected Activity overlay window type")
                finishAndRemoveTask()
                return
            }
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setFinishOnTouchOutside(false)

        mode = runCatching { FloatingMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
            .getOrDefault(FloatingMode.CHAT)
        if (mode == FloatingMode.BUBBLE) mode = FloatingMode.CHOOSER
        intent.getStringExtra(EXTRA_TAB)?.let(workspace::select)

        rectangle = initialBox(intent)
        target = rectangle
        rememberPanelBox(mode, rectangle)
        applyWindowGeometry(rectangle, flagsChanged = true)
        updateGlassPolicy()
        build(mode)
        setContentView(root)
        RenderPolicy.vote(this, root, window.attributes)
        workspace.listen(listener)
        workspace.floatingVisible = mode == FloatingMode.CHAT
        workspace.applyPolicy()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { back() }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = if (insets.isVisible(WindowInsetsCompat.Type.ime()))
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (bottom != imeBottom) {
                if (imeBottom == 0 && mode == FloatingMode.CHAT) chatBox = rectangle
                imeBottom = bottom
                if (mode == FloatingMode.CHAT && !motion.busy) root.post { place(expandedBox(), false) }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val origin = originBox(intent)
        if (origin != null && ValueAnimator.areAnimatorsEnabled()) {
            root.alpha = 1f
            root.post {
                val cx = (origin.x + origin.width / 2 - rectangle.x).coerceIn(0, rectangle.width)
                val cy = (origin.y + origin.height / 2 - rectangle.y).coerceIn(0, rectangle.height)
                motion.reveal(root, cx, cy, d(32).toFloat(), true) { render() }
            }
        }
        service.activityHostReady(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let(workspace::select)
        val requested = runCatching { FloatingMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
            .getOrDefault(mode)
        present(if (requested == FloatingMode.BUBBLE) FloatingMode.CHOOSER else requested)
    }

    override fun onResume() {
        super.onResume()
        if (!destroyed) {
            workspace.floatingVisible = mode == FloatingMode.CHAT
            workspace.applyPolicy()
            RenderPolicy.vote(this, root, window.attributes)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!destroyed) {
            chatBox = null
            chooserBox = null
            place(expandedBox(), true)
        }
    }

    override fun onDestroy() {
        if (!destroyed) {
            destroyed = true
            motion.cancel()
            dismiss.hide(true)
            workspace.unlisten(listener)
            gecko?.let { workspace.detachSurface(it) }
            geckoWindow?.destroy()
            geckoWindow = null
            workspace.floatingVisible = false
            workspace.applyPolicy()
            service.activityHostDestroyed(this)
        }
        super.onDestroy()
    }

    @Deprecated("API 26-32 back compatibility")
    override fun onBackPressed() = back()

    internal fun present(requested: FloatingMode) {
        val next = if (requested == FloatingMode.BUBBLE) FloatingMode.CHOOSER else requested
        if (destroyed || hiding) return
        if (mode == next) {
            render()
            return
        }
        QuickPanel.dismissFor(root)
        motion.cancel()
        root.animate().cancel()
        root.alpha = 1f
        root.scaleX = 1f
        root.scaleY = 1f
        root.translationY = 0f
        geckoWindow?.coverForReveal(false)
        val previous = mode
        if (previous == FloatingMode.CHAT) chatBox = rectangle else chooserBox = rectangle
        if (next == FloatingMode.CHOOSER) {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            imeBottom = 0
        }
        gecko?.let { workspace.detachSurface(it) }
        geckoWindow?.hide()
        mode = next
        workspace.floatingVisible = next == FloatingMode.CHAT
        build(next)
        workspace.applyPolicy()
        val destination = WindowGeometry.fit(panelBox(next) ?: expandedBox(), safeArea())
        place(destination, true)
        render()
        root.getChildAt(0)?.let { content ->
            if (ValueAnimator.areAnimatorsEnabled()) {
                content.animate().cancel()
                content.alpha = .28f
                content.scaleX = .985f
                content.scaleY = .985f
                content.translationY = (if (next == FloatingMode.CHOOSER) d(10) else -d(10)).toFloat()
                content.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(180).setInterpolator(Ui.ease).start()
            }
        }
    }

    internal fun visualEffectsChanged() {
        updateGlassPolicy()
        setPanelBackground(mode)
        invalidateOptionsMenu()
    }

    private fun build(next: FloatingMode) {
        root.removeAllViews()
        pageContainer = null
        list = null
        heading = null
        subtitle = null
        error = null
        count = null
        backControl = null
        root.clipToOutline = true
        setPanelBackground(next)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(4), 0, d(4), 0)
            background = Ui.shape(this@FloatingBrowserActivity, Ui.SURFACE, 0f)
        }
        if (next == FloatingMode.CHAT) {
            backControl = control("back", "Back in webpage") {
                if (workspace.selected?.back == true) workspace.selected?.session?.goBack()
            }
            top.addView(backControl, LinearLayout.LayoutParams(d(48), d(48)))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), 0, 0, 0)
            contentDescription = "Drag floating window"
            isClickable = true
            setOnTouchListener { _, event -> drag(event, false) }
        }
        heading = Ui.text(this, if (next == FloatingMode.CHOOSER) "Your chats" else "ChatGPT", 14f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitle = Ui.text(this, "", 10f, Ui.MUTED).apply {
            maxLines = 1
            setPadding(0, d(3), 0, 0)
        }
        labels.addView(heading)
        labels.addView(subtitle)
        top.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        if (next == FloatingMode.CHAT) {
            count = control("tabs", "Choose another conversation") { present(FloatingMode.CHOOSER) }
            top.addView(count, LinearLayout.LayoutParams(d(48), d(48)))
            top.addView(control("expand", "Open fullscreen") { fullscreen() }, LinearLayout.LayoutParams(d(48), d(48)))
        } else {
            top.addView(control("add", "New floating ChatGPT chat", true) {
                if (workspace.ready) {
                    workspace.create()
                    present(FloatingMode.CHAT)
                }
            }, LinearLayout.LayoutParams(d(48), d(48)))
            top.addView(control("menu", "Workspace menu") {
                FloatingChooserMenu.show(top, workspace) { id -> workspace.select(id); present(FloatingMode.CHAT) }
            }, LinearLayout.LayoutParams(d(48), d(48)))
        }
        top.addView(control("collapse", "Minimize floating window") { minimize() }, LinearLayout.LayoutParams(d(48), d(48)))
        column.addView(top, LinearLayout.LayoutParams(-1, d(52)))

        if (next == FloatingMode.CHOOSER) {
            list = ConversationList(this,
                { id -> workspace.select(id); present(FloatingMode.CHAT) },
                { workspace.close(it) },
                { _, id -> QuickMenus.tabOptions(top, workspace, id) { chosen -> workspace.select(chosen); present(FloatingMode.CHAT) } }
            )
            list?.setPadding(d(8), d(4), d(8), d(4))
            column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            val utility = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(2), 0, d(2), 0)
                background = Ui.shape(this@FloatingBrowserActivity, Ui.SURFACE, 0f)
            }
            utility.addView(MinimizeStrip(false), LinearLayout.LayoutParams(0, d(48), 1f))
            val resize = control("resize", "Resize conversation chooser") { }
            resize.setOnTouchListener { _, event -> drag(event, true) }
            utility.addView(resize, LinearLayout.LayoutParams(d(48), d(48)))
            column.addView(utility, LinearLayout.LayoutParams(-1, d(48)))
        } else {
            if (geckoWindow == null) geckoWindow = RendererArena.createHost(this)
            val content = FrameLayout(this).also { pageContainer = it }
            error = Ui.text(this, "", 13f, Ui.TEXT).apply {
                setPadding(d(20), d(20), d(20), d(20))
                background = Ui.shape(this@FloatingBrowserActivity, Ui.SURFACE, 20f)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setOnClickListener { workspace.retry() }
            }
            content.addView(error, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                setMargins(d(12), 0, d(12), 0)
            })
            column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            val utility = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(2), 0, d(2), 0)
                background = Ui.shape(this@FloatingBrowserActivity, Ui.SURFACE, 0f)
            }
            utility.addView(control("reload", "Refresh floating page") { refreshPage() }, LinearLayout.LayoutParams(d(48), d(48)))
            utility.addView(control("share", "Share floating page") { sharePage() }, LinearLayout.LayoutParams(d(48), d(48)))
            utility.addView(MinimizeStrip(false), LinearLayout.LayoutParams(0, d(48), 1f))
            val resize = control("resize", "Resize floating chat") { }
            resize.setOnTouchListener { _, event -> drag(event, true) }
            utility.addView(resize, LinearLayout.LayoutParams(d(48), d(48)))
            column.addView(utility, LinearLayout.LayoutParams(-1, d(48)))
            val edge = AccessPreferences.get(this).options
            if (edge.enabled && edge.indicator) {
                root.addView(MinimizeStrip(true), FrameLayout.LayoutParams(
                    d(24), d(112), Gravity.CENTER_VERTICAL or if (edge.left) Gravity.LEFT else Gravity.RIGHT
                ))
            }
        }
        RenderPolicy.vote(this, root, window.attributes)
    }

    private fun render() {
        if (destroyed) return
        list?.refresh(workspace)
        if (mode == FloatingMode.CHOOSER) {
            val text = "${workspace.tabs.size} conversations · drag tab icons to reorder"
            if (subtitle?.text != text) subtitle?.text = text
            return
        }
        if (mode != FloatingMode.CHAT) return
        val tab = workspace.selected ?: return
        count?.count = workspace.tabs.size
        backControl?.let {
            val alpha = if (tab.back) 1f else .55f
            if (it.alpha != alpha) it.alpha = alpha
        }
        if (heading?.text != tab.displayName) heading?.text = tab.displayName
        val state = when {
            tab.generating -> "Generating · kept live"
            tab.loading -> "Loading ${tab.progress}% · kept live"
            tab.forceKeepAlive -> "Forced live · ${Policy.host(tab.url)}"
            tab.suspended || tab.session == null -> "Suspended · tap/open to resume"
            else -> "${Policy.host(tab.url)} · live"
        }
        val profileState = if (tab.profileId == ProfilePolicy.DEFAULT_ID) state
            else "${workspace.profileName(tab.profileId)} · $state"
        if (subtitle?.text != profileState) subtitle?.text = profileState

        if (tab.error == null && geckoWindow != null) {
            pageContainer?.let { parent ->
                val current = geckoWindow
                if (current != null && !current.show(parent) && current.transport == RendererArena.Transport.DIRECT_GECKO_SURFACE) {
                    current.destroy()
                    RendererArena.transport = RendererArena.Transport.RELAY_LATEST_BP
                    geckoWindow = RendererArena.createFallback(this)
                    geckoWindow?.show(parent)
                    setPanelBackground(FloatingMode.CHAT)
                }
            }
            val view = gecko
            val session = tab.session
            if (view != null && session != null && session.isOpen) workspace.attachSurface(view, session)
            else if (view?.session != null) workspace.detachSurface(view)
        } else geckoWindow?.hide()

        error?.visibility = if (tab.error == null) View.GONE else View.VISIBLE
        val message = tab.error?.plus("\n\nTap to retry").orEmpty()
        if (error?.text?.toString() != message) error?.text = message
        if (tab.unread && workspace.chatVisible) {
            tab.unread = false
            Replies.clear(this, tab.id)
            workspace.changed(true)
        }
    }

    private fun back() {
        if (workspace.quickMenuVisible) QuickPanel.dismissFor(root)
        else if (ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        else minimize()
    }

    private fun minimize() {
        if (destroyed || hiding) return
        QuickPanel.dismissFor(root)
        hiding = true
        geckoWindow?.hide()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            service.minimizeFromActivity(this)
            return
        }
        root.animate().cancel()
        root.animate().alpha(0f).scaleX(.88f).scaleY(.88f).setDuration(150).setInterpolator(Ui.ease)
            .withEndAction { if (!destroyed) service.minimizeFromActivity(this) }.start()
    }

    private fun fullscreen() {
        QuickPanel.dismissFor(root)
        val intent = Intent(this, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BrowserActivity.EXTRA_TAB, workspace.selectedId)
        }
        try { FullscreenHandoff.launchFromFloating(this, root, geckoWindow, intent) }
        catch (_: RuntimeException) {
            Toast.makeText(this, "Could not open the browser window", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun refreshPage() {
        val session = workspace.selected?.session
        if (session != null && session.isOpen) session.reload() else workspace.retry()
    }

    private fun sharePage() {
        val url = workspace.selected?.url.orEmpty()
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try { startActivity(Intent.createChooser(send, "Share page")) }
        catch (_: RuntimeException) { Toast.makeText(this, "No app is available to share this page.", Toast.LENGTH_LONG).show() }
    }

    internal fun offerExternal(raw: String) {
        Toast.makeText(this, "Open fullscreen to confirm this external-app link.", Toast.LENGTH_LONG).show()
    }

    private fun control(glyph: String, label: String, accent: Boolean = false, click: () -> Unit) =
        GlyphView(this, glyph, label, accent).apply {
            setOnClickListener { click() }
            when (glyph) {
                "back" -> {
                    tooltipText = "Back · hold for Forward, Stop and Refresh"
                    setOnLongClickListener { QuickMenus.navigation(this, workspace); true }
                }
                "collapse" -> {
                    tooltipText = "Minimize · hold to hide in notification"
                    setOnLongClickListener { service.parkFromActivity(this@FloatingBrowserActivity); true }
                }
                "tabs" -> {
                    tooltipText = "Tabs · hold for quick tabs"
                    setOnLongClickListener {
                        QuickMenus.tabs(this, workspace) { id -> workspace.select(id); present(FloatingMode.CHAT) }
                        true
                    }
                }
            }
        }

    private inner class MinimizeStrip(private val vertical: Boolean) : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED }
        private var startX = 0f
        private var startY = 0f
        private var armed = false
        private var active = false
        private var gesture = ToolbarSwipe.NONE

        init {
            isClickable = true
            isFocusable = true
            contentDescription = when {
                vertical -> "Swipe outward to minimize floating window"
                mode == FloatingMode.CHAT -> "Swipe up for chats, left for back, right for forward, or down to minimize floating window"
                else -> "Swipe up for last tab or down to minimize floating window"
            }
            background = Ui.ripple(this@FloatingBrowserActivity, Color.TRANSPARENT, 14f)
            ViewCompat.addAccessibilityAction(this, "Minimize floating window") { _, _ -> minimize(); true }
            if (!vertical && mode == FloatingMode.CHAT) {
                ViewCompat.addAccessibilityAction(this, "Open conversation chooser") { _, _ -> present(FloatingMode.CHOOSER); true }
                ViewCompat.addAccessibilityAction(this, "Back in webpage") { _, _ -> navigateFromPill(false); true }
                ViewCompat.addAccessibilityAction(this, "Forward in webpage") { _, _ -> navigateFromPill(true); true }
            }
            if (!vertical && mode == FloatingMode.CHOOSER) {
                ViewCompat.addAccessibilityAction(this, "Return to last tab") { _, _ -> present(FloatingMode.CHAT); true }
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            paint.color = if (armed) Ui.ACTIVE else Ui.MUTED
            if (vertical) {
                val w = d(4).toFloat()
                val h = min(height - d(24), d(44)).coerceAtLeast(d(18)).toFloat()
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w, w, paint)
            } else {
                val w = min(width - d(24), d(56)).coerceAtLeast(d(28)).toFloat()
                val h = d(if (armed) 5 else 4).toFloat()
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h, h, paint)
            }
        }

        private fun classify(dx: Float, dy: Float): ToolbarSwipe {
            val threshold = maxOf(d(18).toFloat(), slop * 1.15f)
            if (vertical) {
                val left = AccessPreferences.get(this@FloatingBrowserActivity).options.left
                val outward = if (left) -dx else dx
                return if (outward > threshold && outward > abs(dy) * .72f) ToolbarSwipe.MINIMIZE else ToolbarSwipe.NONE
            }
            return ToolbarSwipePolicy.classify(
                dx, dy, threshold,
                horizontalHistory = mode == FloatingMode.CHAT,
                swipeUpChooser = mode == FloatingMode.CHAT,
                swipeUpReturn = mode == FloatingMode.CHOOSER,
                swipeDownMinimize = true
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (hiding) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = true
                    armed = false
                    gesture = ToolbarSwipe.NONE
                    startX = event.rawX
                    startY = event.rawY
                    animate().cancel()
                    translationX = 0f
                    translationY = 0f
                    alpha = 1f
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    active = false
                    armed = false
                    gesture = ToolbarSwipe.NONE
                    reset()
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (active) {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val next = classify(dx, dy)
                    if (next != ToolbarSwipe.NONE && next != gesture) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    gesture = next
                    armed = next != ToolbarSwipe.NONE
                    when {
                        vertical -> { translationX = dx.coerceIn(-d(22).toFloat(), d(22).toFloat()); translationY = 0f }
                        next == ToolbarSwipe.PAGE_BACK || next == ToolbarSwipe.PAGE_FORWARD -> {
                            translationX = dx.coerceIn(-d(22).toFloat(), d(22).toFloat()); translationY = 0f
                        }
                        else -> { translationX = 0f; translationY = dy.coerceIn(-d(22).toFloat(), d(22).toFloat()) }
                    }
                    alpha = if (armed) .62f else .84f
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val accepted = if (event.actionMasked == MotionEvent.ACTION_UP && active) classify(dx, dy) else ToolbarSwipe.NONE
                    active = false
                    armed = false
                    gesture = ToolbarSwipe.NONE
                    reset()
                    when (accepted) {
                        ToolbarSwipe.PAGE_BACK -> navigateFromPill(false)
                        ToolbarSwipe.PAGE_FORWARD -> navigateFromPill(true)
                        ToolbarSwipe.OPEN_CHOOSER -> present(FloatingMode.CHOOSER)
                        ToolbarSwipe.RETURN_TO_TAB -> present(FloatingMode.CHAT)
                        ToolbarSwipe.MINIMIZE -> minimize()
                        else -> Unit
                    }
                }
            }
            return true
        }

        private fun navigateFromPill(forward: Boolean) {
            if (mode != FloatingMode.CHAT) return
            val tab = workspace.selected ?: return
            val session = tab.session?.takeIf { it.isOpen } ?: return
            if (forward) {
                if (tab.forward) session.goForward()
            } else if (tab.back) session.goBack()
        }

        private fun reset() {
            invalidate()
            if (ValueAnimator.areAnimatorsEnabled())
                animate().translationX(0f).translationY(0f).alpha(1f).setDuration(130).setInterpolator(Ui.ease).start()
            else {
                translationX = 0f
                translationY = 0f
                alpha = 1f
            }
        }

        override fun performClick(): Boolean {
            minimize()
            return super.performClick()
        }
    }

    private fun drag(event: MotionEvent, resize: Boolean): Boolean {
        if (hiding) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                QuickPanel.dismissFor(root)
                motion.cancel()
                root.animate().cancel()
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
                root.translationY = 0f
                gestureInitial = rectangle
                gestureX = event.rawX
                gestureY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - gestureX
                val dy = event.rawY - gestureY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) {
                    val raw = if (resize) gestureInitial.copy(
                        width = (gestureInitial.width + dx).toInt().coerceAtLeast(d(280)),
                        height = (gestureInitial.height + dy).toInt().coerceAtLeast(d(260))
                    ) else gestureInitial.copy(
                        x = (gestureInitial.x + dx).toInt(),
                        y = (gestureInitial.y + dy).toInt()
                    )
                    target = WindowGeometry.fit(raw, safeArea())
                    if (!frameQueued) {
                        frameQueued = true
                        root.postOnAnimation {
                            frameQueued = false
                            if (!destroyed && dragging) place(target, false)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val completed = event.actionMasked == MotionEvent.ACTION_UP
                val wasDragging = dragging
                if (wasDragging) place(target, false)
                dragging = false
                if (!completed) place(gestureInitial, false)
                else if (wasDragging) savePosition(resize)
                return true
            }
        }
        return true
    }

    private fun updateGlassPolicy() {
        val enabled = VisualEffects.transparencyEnabled() && Build.VERSION.SDK_INT >= 31 &&
            runCatching { manager.isCrossWindowBlurEnabled }.getOrDefault(false)
        glassBlur = enabled
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { window.setBackgroundBlurRadius(if (enabled) d(18).coerceIn(36, 72) else 0) }
        }
    }

    private fun setPanelBackground(forMode: FloatingMode) {
        root.background = when (forMode) {
            FloatingMode.BUBBLE -> null
            FloatingMode.CHOOSER -> Ui.glassPanel(this, 26f, glassBlur)
            FloatingMode.CHAT -> EmbeddedPageBackground(Ui.glassPanel(this, 26f, glassBlur), root) {
                geckoWindow?.backgroundCutout()
            }
        }
    }

    private fun initialBox(intent: Intent): WindowBox {
        val saved = originBox(intent)
        val expanded = expandedBox()
        return if (saved == null) expanded else WindowGeometry.fit(expanded.copy(
            x = expanded.x.coerceIn(saved.x + saved.width / 2 - expanded.width, saved.x + saved.width / 2),
            y = expanded.y.coerceIn(saved.y + saved.height / 2 - expanded.height, saved.y + saved.height / 2)
        ), safeArea())
    }

    private fun originBox(intent: Intent): WindowBox? {
        val x = intent.getIntExtra(EXTRA_ORIGIN_X, Int.MIN_VALUE)
        if (x == Int.MIN_VALUE) return null
        return WindowBox(
            x,
            intent.getIntExtra(EXTRA_ORIGIN_Y, 0),
            intent.getIntExtra(EXTRA_ORIGIN_W, d(64)),
            intent.getIntExtra(EXTRA_ORIGIN_H, d(64))
        )
    }

    private fun safeArea(): WindowBox {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return WindowBox(
                inset.left + d(4),
                inset.top + d(4),
                (metrics.bounds.width() - inset.left - inset.right - d(8)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - d(8)).coerceAtLeast(1)
            )
        }
        val p = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return WindowBox(d(4), d(28), (p.x - d(8)).coerceAtLeast(1), (p.y - d(60)).coerceAtLeast(1))
    }

    private fun panelBox(forMode: FloatingMode): WindowBox? = when (forMode) {
        FloatingMode.CHAT -> chatBox
        FloatingMode.CHOOSER -> chooserBox
        FloatingMode.BUBBLE -> null
    }

    private fun rememberPanelBox(forMode: FloatingMode, box: WindowBox) {
        when (forMode) {
            FloatingMode.CHAT -> chatBox = box
            FloatingMode.CHOOSER -> chooserBox = box
            FloatingMode.BUBBLE -> Unit
        }
    }

    private fun expandedBox(): WindowBox {
        val safe = safeArea()
        val fallback = if (mode == FloatingMode.CHAT)
            FloatingPanelState(workspace.windowX, workspace.windowY, workspace.windowWidth, workspace.windowHeight)
        else FloatingPanelState(.5f, .25f, .92f, .72f)
        val state = FloatingPanelGeometry.load(this, mode, fallback)
        val width = (safe.width * WindowGeometry.fraction(state.width, .92f)).toInt()
            .coerceAtLeast(d(280)).coerceAtMost(d(560))
        val height = (safe.height * WindowGeometry.fraction(state.height, .72f)).toInt()
            .coerceAtLeast(d(260))
        val resting = panelBox(mode) ?: WindowGeometry.placed(safe, state.x, state.y, width, height)
        val area = if (mode == FloatingMode.CHAT && imeBottom > 0)
            safe.copy(height = (safe.height - imeBottom).coerceAtLeast(d(180))) else safe
        return WindowGeometry.fit(resting, area)
    }

    private fun place(box: WindowBox, flagsChanged: Boolean) {
        if (destroyed) return
        val fitted = WindowGeometry.fit(box, safeArea())
        if (!flagsChanged && rectangle == fitted) return
        rectangle = fitted
        target = fitted
        applyWindowGeometry(fitted, flagsChanged)
    }

    private fun applyWindowGeometry(box: WindowBox, flagsChanged: Boolean) {
        val attrs = window.attributes
        attrs.gravity = Gravity.TOP or Gravity.LEFT
        attrs.x = box.x
        attrs.y = box.y
        attrs.width = box.width
        attrs.height = box.height
        attrs.format = PixelFormat.TRANSLUCENT
        attrs.dimAmount = 0f
        val desired = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (flagsChanged) attrs.flags = (attrs.flags or desired) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = attrs
        RenderPolicy.vote(this, root, attrs)
    }

    private fun savePosition(resized: Boolean) {
        if (imeBottom > 0) return
        val safe = safeArea()
        val nx = if (safe.width > rectangle.width)
            (rectangle.x - safe.x).toFloat() / (safe.width - rectangle.width) else .5f
        val ny = if (safe.height > rectangle.height)
            (rectangle.y - safe.y).toFloat() / (safe.height - rectangle.height) else .5f
        rememberPanelBox(mode, rectangle)
        val old = FloatingPanelGeometry.load(this, mode, if (mode == FloatingMode.CHAT)
            FloatingPanelState(workspace.windowX, workspace.windowY, workspace.windowWidth, workspace.windowHeight)
            else FloatingPanelState(.5f, .25f, .92f, .72f))
        val state = FloatingPanelState(
            nx, ny,
            if (resized) rectangle.width.toFloat() / safe.width else old.width,
            if (resized) rectangle.height.toFloat() / safe.height else old.height
        )
        FloatingPanelGeometry.save(this, mode, state)
        if (mode == FloatingMode.CHAT) {
            workspace.windowX = state.x
            workspace.windowY = state.y
            workspace.windowWidth = state.width
            workspace.windowHeight = state.height
        }
        workspace.checkpoint()
    }

    private fun d(n: Int) = Ui.dp(this, n.toFloat())

    companion object {
        const val EXTRA_MODE = "bubble.floating.activity.mode"
        const val EXTRA_TAB = "bubble.floating.activity.tab"
        const val EXTRA_ORIGIN_X = "bubble.floating.activity.origin.x"
        const val EXTRA_ORIGIN_Y = "bubble.floating.activity.origin.y"
        const val EXTRA_ORIGIN_W = "bubble.floating.activity.origin.w"
        const val EXTRA_ORIGIN_H = "bubble.floating.activity.origin.h"
    }
}
