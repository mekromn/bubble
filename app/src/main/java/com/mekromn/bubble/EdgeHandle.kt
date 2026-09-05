package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import kotlin.math.abs

/** Only a short side strip receives input. No accessibility service, global listener or full-screen overlay. */
internal class EdgeHandle(service: Context, raw: EdgeOptions,
    private val open: (Boolean) -> Unit, private val park: () -> Boolean) {
    private val options = raw.sanitized()
    private val context = windowContext(service)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val view = HandleView()
    private var attached = false
    private var active = false
    private var rejected = false
    private var held = false
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val hold = Runnable {
        if (active && !rejected && !held) { held = true; active = false; view.performLongClick() }
    }
    var box = WindowBox(0, 0, 1, 1)
        private set
    fun attach() {
        check(!attached)
        val safe = safeArea()
        val width = d(options.widthDp).coerceAtMost(safe.width)
        val height = d(options.heightDp).coerceAtMost(safe.height)
        box = WindowBox(if (options.left) safe.x else safe.x + safe.width - width,
            safe.y + ((safe.height - height) * options.position).toInt(), width, height)
        val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = box.x; y = box.y; title = "Bubble edge gesture handle"
        }
        view.setOnClickListener { if (attached) open(false) }
        view.setOnLongClickListener { if (attached) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); open(true) }; true }
        ViewCompat.addAccessibilityAction(view, "Hide in notification") { _, _ -> park() }
        RenderPolicy.vote(context, view, lp)
        manager.addView(view, lp); attached = true
        if (options.indicator && ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0f; view.animate().alpha(1f).setDuration(160).setInterpolator(Ui.ease).start()
        }
    }
    fun destroy() {
        active = false; main.removeCallbacksAndMessages(null); view.animate().cancel()
        if (Build.VERSION.SDK_INT >= 29) view.systemGestureExclusionRects = emptyList()
        if (attached) { attached = false; runCatching { manager.removeView(view) } }
    }
    private fun d(value: Int) = Ui.dp(context, value.toFloat())
    private fun safeArea(): WindowBox {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
            // Lateral mandatory insets are respected. Ordinary Back insets are excluded only in our small strip.
            return WindowBox(inset.left, inset.top + d(24), (metrics.bounds.width() - inset.left - inset.right).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - d(48)).coerceAtLeast(1))
        }
        val size = Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(size)
        return WindowBox(0, d(52), size.x.coerceAtLeast(1), (size.y - d(116)).coerceAtLeast(1))
    }
    private inner class HandleView : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ready = false
        init { isClickable = true; isLongClickable = true; isFocusable = true; contentDescription = "Bubble edge gesture handle" }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
        override fun onDraw(canvas: Canvas) {
            if (!options.indicator && !active) return
            val thickness = d(if (ready) 5 else 3).toFloat()
            val left = if (options.left) d(2).toFloat() else width - thickness - d(2)
            paint.color = if (ready) Ui.ACTIVE else 0x88999999.toInt()
            val top = height * .18f
            canvas.drawRoundRect(left, top, left + thickness, height - top, thickness / 2, thickness / 2, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = true; rejected = false; held = false; ready = false
                    downX = event.rawX; downY = event.rawY; downAt = SystemClock.uptimeMillis()
                    main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> { rejected = true; main.removeCallbacks(hold) }
                MotionEvent.ACTION_MOVE -> if (active && !rejected) {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) main.removeCallbacks(hold)
                    if (EdgePolicy.vertical(dx, dy, slop)) { rejected = true; ready = false }
                    else {
                        val next = EdgePolicy.opens(dx, dy, SystemClock.uptimeMillis() - downAt, options.left, d(32).toFloat())
                        if (next && !ready) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        ready = next
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    val accepted = event.actionMasked == MotionEvent.ACTION_UP && active && !held && !rejected &&
                        EdgePolicy.opens(event.rawX - downX, event.rawY - downY, SystemClock.uptimeMillis() - downAt, options.left, d(32).toFloat())
                    active = false; ready = false; invalidate()
                    if (accepted) performClick()
                }
            }
            return true
        }
        override fun performClick(): Boolean = super.performClick()
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
            info.contentDescription = "Bubble ${if (options.left) "left" else "right"} edge. Swipe inward for chats; hold for the selected chat."
        }
    }
    companion object {
        private fun windowContext(context: Context): Context {
            val base = if (Build.VERSION.SDK_INT >= 30) {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else context
            return ContextThemeWrapper(base, R.style.Theme_Bubble)
        }
    }
}
