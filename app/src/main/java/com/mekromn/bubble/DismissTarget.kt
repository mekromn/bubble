package com.mekromn.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

internal object DismissHit {
    fun contains(x: Float, y: Float, cx: Float, cy: Float, enter: Float, exit: Float, armed: Boolean): Boolean =
        x.isFinite() && y.isFinite() && hypot(x - cx, y - cy) <= if (armed) exit else enter
}

/** Small non-touchable target. It is detached, not left transparent over another application. */
internal class DismissTarget(private val context: Context, private val manager: WindowManager) {
    private var view: Mark? = null
    private var epoch = 0
    var centerX = 0f
        private set
    var centerY = 0f
        private set
    var armed = false
        private set
    val attached: Boolean get() = view != null
    private fun d(n: Int) = Ui.dp(context, n.toFloat())
    fun show(safe: WindowBox) {
        epoch++; armed = false
        view?.let { existing ->
            existing.animate().cancel(); existing.animate().withEndAction(null)
            existing.alpha = 1f; existing.translationY = 0f; existing.scaleX = 1f; existing.scaleY = 1f
            existing.armed = false; existing.invalidate(); return
        }
        val size = d(108)
        centerX = safe.x + safe.width / 2f
        centerY = safe.y + safe.height - d(56).toFloat()
        val mark = Mark(context)
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (centerX - size / 2).toInt(); y = (centerY - size / 2).toInt(); title = "Bubble hide target"; alpha = .8f
        }
        RenderPolicy.vote(context, mark, params)
        try { manager.addView(mark, params); view = mark } catch (_: RuntimeException) { return }
        mark.alpha = 0f; mark.translationY = d(20).toFloat()
        mark.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(Ui.ease).start()
    }
    fun track(x: Float, y: Float): Boolean {
        val mark = view ?: return false
        val next = DismissHit.contains(x, y, centerX, centerY, d(52).toFloat(), d(72).toFloat(), armed)
        if (next != armed) {
            armed = next; mark.armed = next; mark.invalidate()
            mark.animate().scaleX(if (next) 1.12f else 1f).scaleY(if (next) 1.12f else 1f).setDuration(120).setInterpolator(Ui.ease).start()
        }
        return armed
    }
    fun hide(immediate: Boolean = false) {
        val mark = view ?: return
        epoch++; val mine = epoch; armed = false; mark.animate().cancel()
        if (immediate) { remove(mark); return }
        mark.animate().alpha(0f).translationY(d(16).toFloat()).setDuration(130).withEndAction { if (epoch == mine) remove(mark) }.start()
    }
    private fun remove(mark: Mark) {
        mark.animate().cancel(); mark.animate().withEndAction(null)
        if (view === mark) view = null
        try { manager.removeView(mark) } catch (_: RuntimeException) { }
    }
    private class Mark(context: Context) : View(context) {
        var armed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            c.save(); c.scale(width / 108f, height / 108f)
            paint.style = Paint.Style.FILL; paint.color = if (armed) 0xffa84954.toInt() else Ui.SURFACE_HIGH; c.drawCircle(54f, 54f, 29f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = if (armed) 0xffffced3.toInt() else Ui.MUTED; c.drawCircle(54f, 54f, 28f, paint)
            paint.strokeWidth = 2.5f; paint.color = Ui.TEXT
            c.drawLine(46f, 46f, 62f, 62f, paint); c.drawLine(62f, 46f, 46f, 62f, paint)
            paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = 10f
            c.drawText(if (armed) "Release to hide" else "Hide in notification", 54f, 99f, paint)
            c.restore()
        }
    }
}
