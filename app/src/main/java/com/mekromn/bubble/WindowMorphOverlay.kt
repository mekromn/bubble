package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

internal data class MorphFrame(val bitmap: Bitmap, val box: WindowBox)

/**
 * A short-lived, non-interactive TYPE_APPLICATION_OVERLAY used only while the same browser surface
 * crosses the Activity/overlay boundary. The visible object is one card for the whole animation:
 * its bounds, corner radius and source/destination pixels are interpolated in one compositor-owned
 * window. Nothing underneath is resized and Gecko never reflows on animation frames.
 */
internal class WindowMorphOverlay(
    context: Context,
    private val source: MorphFrame,
    private val destinationBox: WindowBox,
    private val sourceRadiusPx: Float,
    private val destinationRadiusPx: Float
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(app).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val sourceView = MorphBitmapView(app, source.bitmap)
    private var destinationView: MorphBitmapView? = null
    private var attached = false
    private var current = source.box
    private var animator: ValueAnimator? = null
    private val display = displayBox(app)
    private val params = WindowManager.LayoutParams(
        display.width,
        display.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = display.x
        y = display.y
        title = "Bubble matched window morph"
    }

    init {
        sourceView.alpha = 1f
        root.addView(sourceView, FrameLayout.LayoutParams(source.bitmap.width.coerceAtLeast(1), source.bitmap.height.coerceAtLeast(1)))
        applyBox(sourceView, source.box, sourceRadiusPx)
    }

    fun attach(onReady: () -> Unit = {}) {
        if (attached) { onReady(); return }
        manager.addView(root, params)
        attached = true
        root.postOnAnimation { if (attached) onReady() }
    }

    fun setDestination(frame: MorphFrame?) {
        if (frame == null || !attached) return
        destinationView?.let { root.removeView(it) }
        val view = MorphBitmapView(app, frame.bitmap).apply { alpha = 0f }
        destinationView = view
        root.addView(view, FrameLayout.LayoutParams(frame.bitmap.width.coerceAtLeast(1), frame.bitmap.height.coerceAtLeast(1)))
        applyBox(view, current, lerpRadius(progressFor(current)))
    }

    /** Animate one geometrically continuous card from source bounds to destination bounds. */
    fun morph(durationMs: Long = 360L, onEnd: () -> Unit) {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            current = destinationBox
            applyCurrent(1f)
            onEnd()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ui.ease
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                current = lerpBox(source.box, destinationBox, t)
                applyCurrent(t)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                    current = destinationBox
                    applyCurrent(1f)
                    onEnd()
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    /** At full size, swap the held morph frame for the real Activity frame before removing it. */
    fun finishWith(frame: MorphFrame?, durationMs: Long = 105L, onEnd: () -> Unit) {
        if (!attached || frame == null || !ValueAnimator.areAnimatorsEnabled()) {
            onEnd(); return
        }
        setDestination(frame)
        val dest = destinationView ?: run { onEnd(); return }
        dest.alpha = 0f
        sourceView.alpha = 1f
        dest.animate().cancel(); sourceView.animate().cancel()
        dest.animate().withLayer().alpha(1f).setDuration(durationMs).setInterpolator(Ui.ease).start()
        sourceView.animate().withLayer().alpha(0f).setDuration(durationMs).setInterpolator(Ui.ease)
            .withEndAction { onEnd() }.start()
    }

    fun detach(recycle: Boolean = true) {
        animator?.cancel(); animator = null
        sourceView.animate().cancel(); destinationView?.animate()?.cancel()
        if (attached) {
            attached = false
            try { manager.removeViewImmediate(root) } catch (_: RuntimeException) { }
        }
        if (recycle) {
            sourceView.release()
            destinationView?.release()
        }
        destinationView = null
    }

    private fun applyCurrent(t: Float) {
        val radius = lerp(sourceRadiusPx, destinationRadiusPx, t)
        applyBox(sourceView, current, radius)
        destinationView?.let { applyBox(it, current, radius) }
        // Chrome/content reflow is hidden inside the same moving geometry. When a prepared
        // destination frame exists (fullscreen -> floating), dissolve inside the shared bounds.
        // When it does not yet exist (floating -> fullscreen), the source MUST remain fully opaque
        // until BrowserActivity is alive behind the full-screen held frame; otherwise the launcher
        // would leak through during expansion.
        val destination = destinationView
        if (destination != null) {
            val destAlpha = smoothstep(.48f, .88f, t)
            destination.alpha = destAlpha
            sourceView.alpha = 1f - smoothstep(.58f, .96f, t)
        } else sourceView.alpha = 1f
        val lift = 1f + .008f * (1f - kotlin.math.abs(t * 2f - 1f))
        sourceView.scaleExtra = lift
        destination?.scaleExtra = lift
    }

    private fun applyBox(view: MorphBitmapView, box: WindowBox, radiusPx: Float) {
        val localX = box.x - display.x
        val localY = box.y - display.y
        val sx = box.width.toFloat() / view.bitmapWidth.coerceAtLeast(1)
        val sy = box.height.toFloat() / view.bitmapHeight.coerceAtLeast(1)
        view.pivotX = 0f; view.pivotY = 0f
        view.translationX = localX.toFloat(); view.translationY = localY.toFloat()
        view.scaleX = sx; view.scaleY = sy
        val localRadius = radiusPx / max(.001f, min(sx, sy))
        view.cornerRadius = localRadius
    }

    private fun progressFor(box: WindowBox): Float {
        val total = kotlin.math.abs(destinationBox.x - source.box.x) + kotlin.math.abs(destinationBox.y - source.box.y) +
            kotlin.math.abs(destinationBox.width - source.box.width) + kotlin.math.abs(destinationBox.height - source.box.height)
        if (total == 0) return 1f
        val done = kotlin.math.abs(box.x - source.box.x) + kotlin.math.abs(box.y - source.box.y) +
            kotlin.math.abs(box.width - source.box.width) + kotlin.math.abs(box.height - source.box.height)
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun lerpRadius(t: Float) = lerp(sourceRadiusPx, destinationRadiusPx, t)

    private class MorphBitmapView(context: Context, bitmap: Bitmap) : View(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        private val clipPath = Path()
        var cornerRadius: Float = 0f
            set(value) { if (field != value) { field = value; invalidate() } }
        var scaleExtra: Float = 1f
            set(value) { if (field != value) { field = value; invalidate() } }
        val bitmapWidth get() = image?.width ?: 1
        val bitmapHeight get() = image?.height ?: 1
        init { setLayerType(LAYER_TYPE_HARDWARE, null) }
        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val save = canvas.save()
            if (cornerRadius > .5f) {
                clipPath.reset(); clipPath.addRoundRect(RectF(0f, 0f, w, h), cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(clipPath)
            }
            if (scaleExtra != 1f) canvas.scale(scaleExtra, scaleExtra, w / 2f, h / 2f)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            canvas.restoreToCount(save)
        }
        fun release() {
            val bitmap = image
            image = null
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        fun displayBox(context: Context): WindowBox {
            val manager = context.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= 30) {
                val bounds = manager.maximumWindowMetrics.bounds
                WindowBox(bounds.left, bounds.top, bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
            } else {
                val p = android.graphics.Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
                WindowBox(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
            }
        }
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun lerpInt(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()
        private fun lerpBox(a: WindowBox, b: WindowBox, t: Float) = WindowBox(
            lerpInt(a.x, b.x, t), lerpInt(a.y, b.y, t),
            lerpInt(a.width, b.width, t).coerceAtLeast(1), lerpInt(a.height, b.height, t).coerceAtLeast(1)
        )
        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
