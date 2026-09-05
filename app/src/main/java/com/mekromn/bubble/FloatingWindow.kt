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
import org.mozilla.geckoview.GeckoView

internal enum class FloatingMode { BUBBLE, CHOOSER, CHAT }

/** One bounded interactive window. Session identity survives every presentation change.
 * Only this animated/clipped browser uses TextureView; fullscreen keeps SurfaceView. */
internal class FloatingWindow(private val service: BubbleService, private val workspace: Workspace) {
    private val context = themedWindowContext(service)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val motion = WindowMotion()
    private val dismiss = DismissTarget(context, manager)
    private var destroyed = false
    private var frameQueued = false
    private var imeBottom = 0
    private var params = WindowManager.LayoutParams()
    private var rectangle = WindowBox(0, 0, 64, 64)
    private var target = rectangle
    private var gestureInitial = rectangle
    private var gestureX = 0f
    private var gestureY = 0f
    private var dragging = false
    private var held = false
    private var hiding = false
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
    val isTransitioning: Boolean get() = motion.busy || hiding
    val dismissTargetAttached: Boolean get() = dismiss.attached
    private val root = object : FrameLayout(context) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            main.post { if (!destroyed) workspace.applyPolicy() }
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && mode != FloatingMode.BUBBLE) {
                if (event.action == KeyEvent.ACTION_UP) back()
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
    private fun back() {
        if (imeBottom > 0) context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        else collapse()
    }
    fun attach(initial: FloatingMode = FloatingMode.BUBBLE) {
        root.isFocusableInTouchMode = true; root.elevation = d(12).toFloat()
        rectangle = headBox(); target = rectangle
        params = WindowManager.LayoutParams(rectangle.width, rectangle.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags(FloatingMode.BUBBLE), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = rectangle.x; y = rectangle.y
            title = "Bubble floating workspace"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        build(FloatingMode.BUBBLE)
        RenderPolicy.vote(context, root, params)
        manager.addView(root, params)
        workspace.listen(listener)
        root.post {
            if (!destroyed && Build.VERSION.SDK_INT >= 33) {
                backDispatcher = root.findOnBackInvokedDispatcher()
                backCallback = android.window.OnBackInvokedCallback { back() }
                backCallback?.let { backDispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it) }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (bottom != imeBottom) {
                imeBottom = bottom
                if (mode == FloatingMode.CHAT) main.post { if (!destroyed && mode == FloatingMode.CHAT && !motion.busy) place(expandedBox(), false) }
            }
            insets
        }
        when (initial) {
            FloatingMode.CHOOSER -> showChooser()
            FloatingMode.CHAT -> openChat(workspace.selectedId)
            else -> if (ValueAnimator.areAnimatorsEnabled()) {
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease).start()
            }
        }
    }
    fun showChooser() = present(FloatingMode.CHOOSER)
    fun openChat(id: String) {
        if (!workspace.ready || workspace.tabs.none { it.id == id }) return
        workspace.select(id); present(FloatingMode.CHAT)
    }
    /** Reverse the reveal to a circle, then move ONLY the small bubble to its remembered position. */
    fun collapse() {
        if (destroyed || mode == FloatingMode.BUBBLE || hiding) return
        main.removeCallbacks(hold); dismiss.hide(true)
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        root.animate().cancel(); root.animate().withEndAction(null); root.alpha = 1f
        val end = headBox()
        val radius = d(32)
        val cx = (end.x + radius - rectangle.x).coerceIn(radius, (rectangle.width - radius).coerceAtLeast(radius))
        val cy = (end.y + radius - rectangle.y).coerceIn(radius, (rectangle.height - radius).coerceAtLeast(radius))
        val startHead = WindowBox(rectangle.x + cx - radius, rectangle.y + cy - radius, d(64), d(64))
        motion.reveal(root, cx, cy, radius.toFloat(), false) {
            if (!destroyed) {
                switchContents(FloatingMode.BUBBLE)
                place(startHead, true)
                motion.move(startHead, end, { place(it, false) }) { render() }
            }
        }
    }
    private fun present(next: FloatingMode) {
        if (destroyed || hiding) return
        if (mode == next) { render(); return }
        main.removeCallbacks(hold); dismiss.hide(true); motion.cancel()
        root.animate().cancel(); root.animate().withEndAction(null); root.alpha = 1f
        val previous = mode; val from = rectangle
        switchContents(next)
        var destination = expandedBox()
        if (previous == FloatingMode.BUBBLE) {
            // Position the final panel to CONTAIN the original circle. It does not jump to
            // an unrelated anchor, and text is never stretched during the reveal.
            val r = d(32); val cx = from.x + r; val cy = from.y + r
            destination = WindowGeometry.fit(destination.copy(
                x = destination.x.coerceIn(cx - destination.width + r, cx - r),
                y = destination.y.coerceIn(cy - destination.height + r, cy - r)), safeArea())
            place(destination, true); render()
            val localX = cx - destination.x; val localY = cy - destination.y
            val mark = BubbleMark(context).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            root.addView(mark, FrameLayout.LayoutParams(d(64), d(64)).apply { leftMargin = localX - r; topMargin = localY - r })
            motion.reveal(root, localX, localY, r.toFloat(), true) {
                if (mark.parent === root) root.removeView(mark)
            }
            mark.animate().alpha(0f).setDuration(110).start()
        } else {
            // Chooser <-> chat stays in the same container. A small content fade indicates
            // substitution, not minimize/maximize; the web surface is not scaled or cached.
            place(destination, true); render()
            root.getChildAt(0)?.let { content ->
                if (ValueAnimator.areAnimatorsEnabled()) {
                    content.alpha = .35f
                    content.animate().alpha(1f).setDuration(140).setInterpolator(Ui.ease).start()
                }
            }
        }
    }
    private fun switchContents(next: FloatingMode) {
        gecko?.let { workspace.detachSurface(it); (it.parent as? ViewGroup)?.removeView(it) }
        mode = next; workspace.floatingVisible = next == FloatingMode.CHAT
        build(next); workspace.applyPolicy()
    }
    private fun build(next: FloatingMode) {
        root.removeAllViews(); list = null; heading = null; subtitle = null; error = null; bubble = null; count = null
        root.clipToOutline = next != FloatingMode.BUBBLE
        root.background = if (next == FloatingMode.BUBBLE) null else Ui.shape(context, Ui.BG, 26f, Ui.LINE)
        if (next == FloatingMode.BUBBLE) {
            val mark = BubbleMark(context); bubble = mark
            mark.setOnClickListener { showChooser() }
            mark.setOnLongClickListener { openChat(workspace.selectedId); true }
            mark.setOnTouchListener { _, event -> drag(event, false, true) }
            root.addView(mark, FrameLayout.LayoutParams(-1, -1))
            accessibilityMoves(mark); return
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), 0, d(4), 0)
            setBackgroundColor(Ui.SURFACE)
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
        labels.addView(heading); labels.addView(subtitle); top.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        if (next == FloatingMode.CHAT) {
            count = control("tabs", "Choose another conversation") { showChooser() }
            top.addView(count, LinearLayout.LayoutParams(d(48), d(48)))
            top.addView(control("expand", "Open fullscreen") { fullscreen() }, LinearLayout.LayoutParams(d(48), d(48)))
        } else top.addView(control("add", "New floating ChatGPT chat", true) {
            if (workspace.ready) openChat(workspace.create().id)
        }, LinearLayout.LayoutParams(d(48), d(48)))
        top.addView(control("collapse", "Minimize floating window") { collapse() }, LinearLayout.LayoutParams(d(48), d(48)))
        column.addView(top, LinearLayout.LayoutParams(-1, d(52)))
        if (next == FloatingMode.CHOOSER) {
            list = ConversationList(context, { openChat(it) }, { workspace.close(it) })
            column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            column.addView(Ui.text(context, "ChatGPT reply alerts · sound settings", 11f, Ui.BLUE).apply {
                gravity = Gravity.CENTER; background = Ui.ripple(context)
                contentDescription = "ChatGPT notification settings"; setOnClickListener { Replies.settings(context) }
            }, LinearLayout.LayoutParams(-1, d(48)))
        } else {
            val web = gecko ?: LiveGeckoView(context).also { it.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW); gecko = it }
            val content = FrameLayout(context)
            content.addView(web, FrameLayout.LayoutParams(-1, -1))
            error = Ui.text(context, "", 13f, Ui.TEXT).apply {
                setPadding(d(20), d(20), d(20), d(20)); background = Ui.shape(context, Ui.SURFACE, 20f)
                gravity = Gravity.CENTER; visibility = View.GONE; setOnClickListener { workspace.retry() }
            }
            content.addView(error, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(d(12), 0, d(12), 0) })
            column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            val resize = control("resize", "Resize floating chat") { }
            resize.setOnTouchListener { _, event -> drag(event, true, false) }
            root.addView(resize, FrameLayout.LayoutParams(d(48), d(32), Gravity.BOTTOM or Gravity.RIGHT))
            content.setPadding(0, 0, 0, d(24))
        }
        RenderPolicy.vote(context, root, params)
    }
    private fun control(glyph: String, label: String, accent: Boolean = false, click: () -> Unit) =
        GlyphView(context, glyph, label, accent).apply { setOnClickListener { click() } }
    private fun render() {
        if (destroyed) return
        bubble?.update(workspace.tabs.size, workspace.tabs.count { it.unread }, workspace.tabs.any { it.generating })
        list?.refresh(workspace)
        if (mode == FloatingMode.CHOOSER) {
            val label = "${workspace.tabs.size} conversations · drag to move"
            if (subtitle?.text != label) subtitle?.text = label
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
        val message = tab.error?.plus("\n\nTap to retry").orEmpty()
        if (error?.text?.toString() != message) error?.text = message
        if (tab.unread) { tab.unread = false; Replies.clear(context, tab.id); workspace.changed(true) }
    }
    private fun fullscreen() {
        try { service.startActivity(Intent(service, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BrowserActivity.EXTRA_TAB, workspace.selectedId)
        }) } catch (_: RuntimeException) { Toast.makeText(context, "Could not open the browser window", Toast.LENGTH_SHORT).show() }
    }
    fun offerExternal(raw: String) { Toast.makeText(context, "Open fullscreen to confirm this external-app link.", Toast.LENGTH_LONG).show() }
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
        val p = Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return WindowBox(d(4), d(28), (p.x - d(8)).coerceAtLeast(1), (p.y - d(60)).coerceAtLeast(1))
    }
    private fun headBox(): WindowBox = WindowGeometry.placed(safeArea(), workspace.bubbleX, workspace.bubbleY, d(64), d(64))
    private fun expandedBox(): WindowBox {
        val safe = safeArea()
        val width = (safe.width * WindowGeometry.fraction(workspace.windowWidth, .92f)).toInt().coerceAtLeast(d(280)).coerceAtMost(d(560))
        val height = (safe.height * WindowGeometry.fraction(workspace.windowHeight, .72f)).toInt().coerceAtLeast(d(260))
        val area = if (mode == FloatingMode.CHAT && imeBottom > 0) safe.copy(height = (safe.height - imeBottom).coerceAtLeast(d(180))) else safe
        return WindowGeometry.placed(area, workspace.windowX, workspace.windowY, width, height)
    }
    private fun place(box: WindowBox, flagsChanged: Boolean) {
        if (destroyed) return
        val fitted = WindowGeometry.fit(box, safeArea())
        if (!flagsChanged && rectangle == fitted) return
        rectangle = fitted; target = fitted
        params.x = fitted.x; params.y = fitted.y; params.width = fitted.width; params.height = fitted.height; params.flags = flags(mode)
        try { manager.updateViewLayout(root, params) } catch (_: RuntimeException) { service.stopSelf() }
    }
    private fun drag(event: MotionEvent, resize: Boolean, isHead: Boolean): Boolean {
        if (hiding) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(hold); motion.cancel()
                root.animate().cancel(); root.animate().withEndAction(null); root.alpha = 1f
                root.scaleX = 1f; root.scaleY = 1f
                gestureInitial = rectangle; gestureX = event.rawX; gestureY = event.rawY; dragging = false; held = false
                if (isHead) main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                val dx = event.rawX - gestureX; val dy = event.rawY - gestureY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true; main.removeCallbacks(hold)
                    if (isHead && service.canPark()) dismiss.show(safeArea())
                }
                if (dragging) {
                    val raw = if (resize) gestureInitial.copy(width = (gestureInitial.width + dx).toInt().coerceAtLeast(d(280)), height = (gestureInitial.height + dy).toInt().coerceAtLeast(d(260)))
                        else gestureInitial.copy(x = (gestureInitial.x + dx).toInt(), y = (gestureInitial.y + dy).toInt())
                    var projected = raw
                    if (isHead && dismiss.attached) {
                        val before = dismiss.armed
                        val armed = dismiss.track(raw.x + raw.width / 2f, raw.y + raw.height / 2f)
                        if (armed && !before) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (armed) projected = raw.copy(x = (dismiss.centerX - raw.width / 2).toInt(), y = (dismiss.centerY - raw.height / 2).toInt())
                    }
                    target = WindowGeometry.fit(projected, safeArea())
                    if (!frameQueued) {
                        frameQueued = true
                        root.postOnAnimation { frameQueued = false; if (!destroyed && dragging) place(target, false) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                val completed = event.actionMasked == MotionEvent.ACTION_UP
                val shouldHide = completed && dragging && isHead && dismiss.armed
                if (dragging) place(target, false)
                dragging = false
                if (shouldHide) {
                    hiding = true; dismiss.hide()
                    root.pivotX = root.width / 2f; root.pivotY = root.height / 2f
                    root.animate().scaleX(.35f).scaleY(.35f).alpha(0f).setDuration(130).setInterpolator(Ui.ease).withEndAction {
                        if (!destroyed && !service.park()) {
                            hiding = false; root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f; place(headBox(), false)
                        }
                    }.start()
                } else {
                    dismiss.hide()
                    if (!completed) place(gestureInitial, false)
                    else if (rectangle != gestureInitial) savePosition(resize)
                    else if (!held && isHead) bubble?.performClick()
                }
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
            if (resized && imeBottom == 0) { workspace.windowWidth = rectangle.width.toFloat() / safe.width; workspace.windowHeight = rectangle.height.toFloat() / safe.height }
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
        ViewCompat.addAccessibilityAction(view, "Hide in notification") { _, _ -> service.park() }
    }
    fun configurationChanged() { if (!destroyed) { motion.cancel(); dismiss.hide(true); place(if (mode == FloatingMode.BUBBLE) headBox() else expandedBox(), false) } }
    fun destroy() {
        if (destroyed) return
        destroyed = true; motion.cancel(); dismiss.hide(true)
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
        val newReply = count > unread
        label = next; unread = count; busy = generating
        contentDescription = "Choose a conversation, $total tabs, $count unread${if (busy) ", generating" else ""}"
        invalidate()
        if (newReply && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            animate().cancel(); scaleX = .92f; scaleY = .92f
            animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(Ui.ease).start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 64f, height / 64f)
        paint.style = Paint.Style.FILL; paint.color = Ui.SURFACE; canvas.drawCircle(32f, 32f, 29f, paint)
        paint.style = Paint.Style.STROKE; paint.color = if (busy) Ui.MINT else Ui.BLUE; paint.strokeWidth = 1.8f
        canvas.drawCircle(32f, 32f, 28f, paint)
        paint.strokeWidth = 2f; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawRoundRect(18f, 19f, 45f, 40f, 7f, 7f, paint)
        path.reset(); path.moveTo(24f, 40f); path.lineTo(24f, 46f); path.lineTo(31f, 40f); canvas.drawPath(path, paint)
        canvas.drawLine(25f, 28f, 38f, 28f, paint)
        paint.style = Paint.Style.FILL; paint.color = if (unread > 0) Ui.MINT else Ui.BLUE; canvas.drawCircle(51f, 13f, 10f, paint)
        paint.color = Ui.BG; paint.textSize = 10f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, 51f, 16.5f, paint); canvas.restore()
    }
    override fun performClick(): Boolean = super.performClick()
}
