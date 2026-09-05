package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

internal enum class FloatingMode { BUBBLE, CHOOSER, CHAT }

/** One small application-overlay window, never a fullscreen transparent touch interceptor.
 * The browser's TextureView is used only here: it supports rounded clipping and transform-only
 * transitions. Fullscreen retains the faster SurfaceView. Both display the SAME GeckoSession. */
internal class FloatingWindow(private val service: BubbleService, private val workspace: Workspace) {
    private val context = themedWindowContext(service)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var frameQueued = false
    private var animationEpoch = 0
    private var imeBottom = 0
    private var params = WindowManager.LayoutParams()
    private var rectangle = WindowBox(0, 0, 68, 68)
    private var target = rectangle
    private var gestureInitial = rectangle
    private var gestureX = 0f
    private var gestureY = 0f
    private var dragging = false
    private var held = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable { if (!dragging && mode == FloatingMode.BUBBLE) { held = true; openChat(workspace.selectedId) } }
    private var list: ConversationList? = null
    private var heading: TextView? = null
    private var subtitle: TextView? = null
    private var error: TextView? = null
    private var count: GlyphView? = null
    private var bubble: BubbleMark? = null
    private var gecko: LiveGeckoView? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null
    var mode = FloatingMode.BUBBLE
        private set
    val geckoView: GeckoView? get() = gecko
    val box: WindowBox get() = rectangle
    private val root = object : FrameLayout(context) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            main.post { if (!destroyed) workspace.applyPolicy() }
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && mode != FloatingMode.BUBBLE) {
                if (event.action == KeyEvent.ACTION_UP) collapse()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE && mode == FloatingMode.CHOOSER) { collapse(); return true }
            return super.onTouchEvent(event)
        }
    }
    private val listener: () -> Unit = { render() }

    fun attach(initial: FloatingMode = FloatingMode.BUBBLE) {
        root.isFocusableInTouchMode = true
        root.elevation = d(12).toFloat()
        root.setPadding(0, 0, 0, 0)
        rectangle = headBox(); target = rectangle
        params = WindowManager.LayoutParams(rectangle.width, rectangle.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags(FloatingMode.BUBBLE), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rectangle.x; y = rectangle.y
            title = "Bubble floating workspace"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            preferredRefreshRate = 120f
        }
        build(FloatingMode.BUBBLE)
        manager.addView(root, params)
        workspace.listen(listener)
        if (Build.VERSION.SDK_INT >= 35) root.setRequestedFrameRate(120f)
        root.post {
            if (!destroyed && Build.VERSION.SDK_INT >= 33) {
                backDispatcher = root.findOnBackInvokedDispatcher()
                backCallback = android.window.OnBackInvokedCallback { collapse() }
                backCallback?.let { backDispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it) }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (bottom != imeBottom) {
                imeBottom = bottom
                if (mode == FloatingMode.CHAT) main.post { if (!destroyed && mode == FloatingMode.CHAT) place(expandedBox(), false) }
            }
            insets
        }
        when (initial) {
            FloatingMode.CHOOSER -> showChooser()
            FloatingMode.CHAT -> openChat(workspace.selectedId)
            else -> {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    root.scaleX = .65f; root.scaleY = .65f; root.alpha = 0f
                    root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(Ui.ease).start()
                }
            }
        }
    }
    fun showChooser() = present(FloatingMode.CHOOSER)
    fun openChat(id: String) {
        if (!workspace.ready || workspace.tabs.none { it.id == id }) return
        workspace.select(id)
        present(FloatingMode.CHAT)
    }
    fun collapse() {
        if (destroyed || mode == FloatingMode.BUBBLE) return
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        main.removeCallbacks(hold)
        val destination = headBox()
        animationEpoch++
        val epoch = animationEpoch
        root.animate().cancel(); root.animate().withEndAction(null)
        if (!ValueAnimator.areAnimatorsEnabled()) { present(FloatingMode.BUBBLE, false); return }
        root.pivotX = 0f; root.pivotY = 0f
        root.animate().scaleX(destination.width.toFloat() / rectangle.width)
            .scaleY(destination.height.toFloat() / rectangle.height)
            .translationX((destination.x - rectangle.x).toFloat()).translationY((destination.y - rectangle.y).toFloat())
            .alpha(.12f).setDuration(240).setInterpolator(Ui.ease).withEndAction {
                if (!destroyed && animationEpoch == epoch) present(FloatingMode.BUBBLE, false)
            }.start()
    }
    private fun present(next: FloatingMode, animated: Boolean = true) {
        if (destroyed) return
        val from = WindowBox((rectangle.x + root.translationX).toInt(), (rectangle.y + root.translationY).toInt(),
            (rectangle.width * root.scaleX).toInt().coerceAtLeast(1), (rectangle.height * root.scaleY).toInt().coerceAtLeast(1))
        animationEpoch++
        root.animate().cancel(); root.animate().withEndAction(null)
        root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f; root.translationX = 0f; root.translationY = 0f
        gecko?.let { workspace.detachSurface(it); (it.parent as? ViewGroup)?.removeView(it) }
        mode = next
        workspace.floatingVisible = next == FloatingMode.CHAT
        build(next)
        val destination = if (next == FloatingMode.BUBBLE) headBox() else expandedBox()
        place(destination, true)
        render()
        workspace.applyPolicy()
        if (animated && ValueAnimator.areAnimatorsEnabled()) {
            val epoch = animationEpoch
            root.pivotX = 0f; root.pivotY = 0f
            root.scaleX = (from.width.toFloat() / destination.width).coerceIn(.1f, 2f)
            root.scaleY = (from.height.toFloat() / destination.height).coerceIn(.1f, 2f)
            root.translationX = (from.x - destination.x).toFloat(); root.translationY = (from.y - destination.y).toFloat()
            root.alpha = .15f
            // Change layout once, then animate GPU transforms; never relayout Gecko every frame.
            root.postOnAnimation {
                if (!destroyed && epoch == animationEpoch) root.animate().scaleX(1f).scaleY(1f)
                    .translationX(0f).translationY(0f).alpha(1f).setDuration(300).setInterpolator(Ui.ease).start()
            }
        }
    }
    private fun build(next: FloatingMode) {
        root.removeAllViews(); list = null; heading = null; subtitle = null; error = null; bubble = null; count = null
        root.clipToOutline = next != FloatingMode.BUBBLE
        root.background = if (next == FloatingMode.BUBBLE) null else Ui.shape(context, Ui.BG, 26f, Ui.LINE)
        root.contentDescription = null
        if (next == FloatingMode.BUBBLE) {
            val mark = BubbleMark(context)
            bubble = mark
            mark.setOnClickListener { showChooser() }
            mark.setOnLongClickListener { openChat(workspace.selectedId); true }
            mark.setOnTouchListener { _, event -> drag(event, false, true) }
            root.addView(mark, FrameLayout.LayoutParams(-1, -1))
            accessibilityMoves(mark)
            return
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), 0, d(4), 0)
            background = Ui.shape(context, Ui.SURFACE, 0f)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Drag floating window"; isClickable = true
            setOnTouchListener { _, event -> drag(event, false, false) }
        }
        heading = Ui.text(context, if (next == FloatingMode.CHOOSER) "Your chats" else "ChatGPT", 14f, Ui.TEXT, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitle = Ui.text(context, "", 10f, Ui.MUTED).apply { maxLines = 1; setPadding(0, d(3), 0, 0) }
        labels.addView(heading); labels.addView(subtitle)
        top.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        if (next == FloatingMode.CHAT) {
            count = control("tabs", "Choose another conversation") { showChooser() }
            top.addView(count, LinearLayout.LayoutParams(d(48), d(48)))
            top.addView(control("expand", "Open fullscreen") { fullscreen(false) }, LinearLayout.LayoutParams(d(48), d(48)))
        } else top.addView(control("add", "New floating ChatGPT chat", true) {
            if (workspace.ready) openChat(workspace.create().id)
        }, LinearLayout.LayoutParams(d(48), d(48)))
        top.addView(control("collapse", "Minimize floating window") { collapse() }, LinearLayout.LayoutParams(d(48), d(48)))
        column.addView(top, LinearLayout.LayoutParams(-1, d(52)))
        if (next == FloatingMode.CHOOSER) {
            list = ConversationList(context, { openChat(it) }, { workspace.close(it) })
            column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            column.addView(Ui.text(context, "ALL TABS LIVE  ·  TAP A CHAT TO OPEN", 9f, Ui.MUTED).apply {
                gravity = Gravity.CENTER; letterSpacing = .06f
            }, LinearLayout.LayoutParams(-1, d(28)))
        } else {
            val web = gecko ?: LiveGeckoView(context).also {
                it.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                gecko = it
            }
            val content = FrameLayout(context)
            content.addView(web, FrameLayout.LayoutParams(-1, -1))
            error = Ui.text(context, "", 13f, Ui.TEXT).apply {
                setPadding(d(20), d(20), d(20), d(20)); background = Ui.shape(context, Ui.SURFACE, 20f)
                gravity = Gravity.CENTER; visibility = View.GONE; setOnClickListener { workspace.retry() }
            }
            content.addView(error, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(d(12), 0, d(12), 0) })
            column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            // A dedicated handle prevents accidental resize while scrolling or typing in the page.
            val resize = control("resize", "Resize floating chat") { }
            resize.setOnTouchListener { _, event -> drag(event, true, false) }
            root.addView(resize, FrameLayout.LayoutParams(d(48), d(32), Gravity.BOTTOM or Gravity.RIGHT))
            // Leave the site's composer clear of the resize hit area.
            content.setPadding(0, 0, 0, d(24))
        }
    }
    private fun control(glyph: String, label: String, accent: Boolean = false, click: () -> Unit) =
        GlyphView(context, glyph, label, accent).apply { setOnClickListener { click() } }
    private fun render() {
        if (destroyed) return
        bubble?.update(workspace.tabs.size, workspace.tabs.count { it.unread }, workspace.tabs.any { it.generating })
        list?.refresh(workspace)
        if (mode == FloatingMode.CHOOSER) {
            subtitle?.text = "${workspace.tabs.size} conversations · drag to move"
            return
        }
        if (mode != FloatingMode.CHAT) return
        val tab = workspace.selected ?: return
        count?.count = workspace.tabs.size
        val title = tab.title.ifBlank { "ChatGPT" }
        if (heading?.text != title) heading?.text = title
        val state = when { tab.generating -> "Generating · kept live"; tab.loading -> "Loading ${tab.progress}%"; else -> "${Policy.host(tab.url)} · live" }
        if (subtitle?.text != state) subtitle?.text = state
        gecko?.let { view ->
            val session = tab.session
            if (session != null && session.isOpen) workspace.attachSurface(view, session)
            else if (view.session != null) workspace.detachSurface(view)
        }
        error?.visibility = if (tab.error == null) View.GONE else View.VISIBLE
        error?.text = tab.error?.plus("\n\nTap to retry").orEmpty()
        if (tab.unread) { tab.unread = false; Replies.clear(context, tab.id); workspace.changed(true) }
    }
    private fun fullscreen(pip: Boolean) {
        try {
            service.startActivity(Intent(service, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(BrowserActivity.EXTRA_TAB, workspace.selectedId)
                putExtra(BrowserActivity.EXTRA_PIP, pip)
            })
        } catch (_: RuntimeException) { Toast.makeText(context, "Could not open the browser window", Toast.LENGTH_SHORT).show() }
    }
    fun offerExternal(raw: String) {
        // External app prompts require an Activity. Keep the current page/session and ask there.
        Toast.makeText(context, "Open fullscreen to confirm this external-app link.", Toast.LENGTH_LONG).show()
    }
    private fun flags(next: FloatingMode): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return if (next == FloatingMode.BUBBLE) base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else base
    }
    private fun safeArea(): WindowBox {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return WindowBox(inset.left + d(4), inset.top + d(4),
                (metrics.bounds.width() - inset.left - inset.right - d(8)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - d(8)).coerceAtLeast(1))
        }
        val p = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return WindowBox(d(4), d(28), (p.x - d(8)).coerceAtLeast(1), (p.y - d(60)).coerceAtLeast(1))
    }
    private fun headBox(): WindowBox = WindowGeometry.placed(safeArea(), workspace.bubbleX, workspace.bubbleY, d(64), d(64))
    private fun expandedBox(): WindowBox {
        val safe = safeArea()
        val width = (safe.width * WindowGeometry.fraction(workspace.windowWidth, .92f)).toInt().coerceAtLeast(d(280)).coerceAtMost(d(560))
        val height = if (mode == FloatingMode.CHOOSER) minOf(d(470), (safe.height * .7f).toInt())
            else (safe.height * WindowGeometry.fraction(workspace.windowHeight, .72f)).toInt().coerceAtLeast(d(260))
        val area = if (mode == FloatingMode.CHAT && imeBottom > 0) safe.copy(height = (safe.height - imeBottom).coerceAtLeast(d(180))) else safe
        return WindowGeometry.placed(area, workspace.windowX, workspace.windowY, width, height)
    }
    private fun place(box: WindowBox, flagsChanged: Boolean) {
        if (destroyed) return
        val fitted = WindowGeometry.fit(box, safeArea())
        if (!flagsChanged && rectangle == fitted) return
        rectangle = fitted; target = fitted
        params.x = fitted.x; params.y = fitted.y; params.width = fitted.width; params.height = fitted.height
        params.flags = flags(mode)
        try { manager.updateViewLayout(root, params) } catch (_: RuntimeException) { service.stopSelf() }
    }
    private fun drag(event: MotionEvent, resize: Boolean, isHead: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(hold)
                gestureInitial = rectangle; gestureX = event.rawX; gestureY = event.rawY
                dragging = false; held = false
                if (isHead) main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                val dx = event.rawX - gestureX; val dy = event.rawY - gestureY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true; main.removeCallbacks(hold); animationEpoch++
                    root.animate().cancel(); root.animate().withEndAction(null)
                    root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f; root.translationX = 0f; root.translationY = 0f
                }
                if (dragging) {
                    val raw = if (resize) gestureInitial.copy(width = (gestureInitial.width + dx).toInt().coerceAtLeast(d(280)),
                        height = (gestureInitial.height + dy).toInt().coerceAtLeast(d(260)))
                        else gestureInitial.copy(x = (gestureInitial.x + dx).toInt(), y = (gestureInitial.y + dy).toInt())
                    target = WindowGeometry.fit(raw, safeArea())
                    if (!frameQueued) {
                        frameQueued = true
                        root.postOnAnimation { frameQueued = false; if (!destroyed) place(target, false) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                if (dragging) { place(target, false); savePosition(resize) }
                else if (!held && isHead && event.actionMasked == MotionEvent.ACTION_UP) bubble?.performClick()
                return true
            }
        }
        return true
    }
    private fun savePosition(resized: Boolean) {
        val safe = safeArea()
        val nx = if (safe.width > rectangle.width) (rectangle.x - safe.x).toFloat() / (safe.width - rectangle.width) else .5f
        val ny = if (safe.height > rectangle.height) (rectangle.y - safe.y).toFloat() / (safe.height - rectangle.height) else .5f
        if (mode == FloatingMode.BUBBLE) { workspace.bubbleX = nx; workspace.bubbleY = ny }
        else {
            workspace.windowX = nx; workspace.windowY = ny
            if (resized && imeBottom == 0) {
                workspace.windowWidth = rectangle.width.toFloat() / safe.width
                workspace.windowHeight = rectangle.height.toFloat() / safe.height
            }
        }
        workspace.checkpoint()
    }
    private fun accessibilityMoves(view: View) {
        listOf("Move left" to (-1 to 0), "Move right" to (1 to 0), "Move up" to (0 to -1), "Move down" to (0 to 1)).forEach { (name, direction) ->
            ViewCompat.addAccessibilityAction(view, name) { _, _ ->
                place(rectangle.copy(x = rectangle.x + direction.first * d(40), y = rectangle.y + direction.second * d(40)), false)
                savePosition(false); true
            }
        }
        ViewCompat.addAccessibilityAction(view, "Hide bubble") { _, _ -> service.stopSelf(); true }
    }
    fun configurationChanged() { if (!destroyed) place(if (mode == FloatingMode.BUBBLE) headBox() else expandedBox(), false) }
    fun destroy() {
        if (destroyed) return
        destroyed = true; animationEpoch++
        workspace.unlisten(listener); main.removeCallbacksAndMessages(null)
        root.animate().cancel(); root.animate().withEndAction(null)
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        gecko?.let { workspace.detachSurface(it) }
        workspace.floatingVisible = false; workspace.applyPolicy()
        try { manager.removeView(root) } catch (_: RuntimeException) { }
        gecko = null
    }
    private fun d(n: Int) = Ui.dp(context, n.toFloat())
    companion object {
        private fun themedWindowContext(service: Context): Context {
            val base = if (Build.VERSION.SDK_INT >= 30) {
                val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else service
            return ContextThemeWrapper(base, R.style.Theme_Bubble)
        }
    }
}

/** No idle animation loop: changes repaint once; expansion and collapse are GPU property motion. */
private class BubbleMark(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var label = "1"
    private var unread = 0
    private var busy = false
    init { isClickable = true; isFocusable = true; contentDescription = "Choose a conversation, 1 tab" }
    fun update(total: Int, count: Int, generating: Boolean) {
        val next = if (total > 99) "99+" else total.toString()
        if (label == next && unread == count && busy == generating) return
        label = next; unread = count; busy = generating
        contentDescription = "Choose a conversation, $total tabs, $count unread${if (busy) ", generating" else ""}"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 64f, height / 64f)
        paint.style = Paint.Style.FILL; paint.color = Ui.SURFACE
        canvas.drawCircle(32f, 32f, 29f, paint)
        paint.style = Paint.Style.STROKE; paint.color = if (busy) Ui.MINT else Ui.BLUE; paint.strokeWidth = 1.8f
        canvas.drawCircle(32f, 32f, 28f, paint)
        paint.strokeWidth = 2f; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawRoundRect(18f, 19f, 45f, 40f, 7f, 7f, paint)
        path.reset(); path.moveTo(24f, 40f); path.lineTo(24f, 46f); path.lineTo(31f, 40f); canvas.drawPath(path, paint)
        canvas.drawLine(25f, 28f, 38f, 28f, paint)
        paint.style = Paint.Style.FILL; paint.color = if (unread > 0) Ui.MINT else Ui.BLUE
        canvas.drawCircle(51f, 13f, 10f, paint)
        paint.color = Ui.BG; paint.textSize = 10f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, 51f, 16.5f, paint)
        canvas.restore()
    }
    override fun performClick(): Boolean = super.performClick()
}
