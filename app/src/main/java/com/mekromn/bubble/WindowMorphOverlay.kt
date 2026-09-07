package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

internal data class MorphFrame(val bitmap: Bitmap, val box: WindowBox)

/**
 * Temporary, non-interactive compositor surface used only while the same browser window crosses
 * the Activity / TYPE_APPLICATION_OVERLAY boundary.
 *
 * Unlike a normal View scale animation, the bitmap is never stretched independently on X/Y.
 * Container bounds morph continuously while each source/destination frame is center-cropped with
 * a uniform scale, matching the perceptual behavior of a high-quality container transform: text
 * and icons never become rubbery as the fullscreen and floating aspect ratios diverge.
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
    private val display = displayBox(app)
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
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        title = "Bubble matched window morph"
    }

    init {
        sourceView.alpha = 1f
        root.addView(sourceView, FrameLayout.LayoutParams(-1, -1))
        applyBox(sourceView, source.box, sourceRadiusPx)
    }

    fun attach(onReady: () -> Unit = {}) {
        if (attached) { onReady(); return }
        manager.addView(root, params)
        attached = true
        // Wait for one committed overlay frame before allowing the source Activity/card to hide.
        root.postOnAnimation { if (attached) onReady() }
    }

    fun setDestination(frame: MorphFrame?) {
        if (frame == null || !attached) return
        destinationView?.let {
            root.removeView(it)
            it.release()
        }
        val view = MorphBitmapView(app, frame.bitmap).apply { alpha = 0f }
        destinationView = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
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
        var cancelled = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ui.ease
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                current = lerpBox(source.box, destinationBox, t)
                applyCurrent(t)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                    if (animator === animation) animator = null
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                    if (cancelled || !attached) return
                    current = destinationBox
                    applyCurrent(1f)
                    onEnd()
                }
            })
            start()
        }
    }

    /** At full size, dissolve the held morph frame into a real PixelCopy of BrowserActivity. */
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
            .withEndAction { if (attached) onEnd() }.start()
    }

    fun detach(recycle: Boolean = true) {
        val running = animator
        animator = null
        running?.cancel()
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

        // Fade-through happens *inside the same moving container*. With a prepared floating
        // destination, its reflowed chrome/page starts contributing only near the landing zone.
        // During floating -> fullscreen there is no destination Activity yet, so the held card is
        // intentionally opaque until it covers the display and BrowserActivity is ready behind it.
        val destination = destinationView
        if (destination != null) {
            destination.alpha = smoothstep(.52f, .90f, t)
            sourceView.alpha = 1f - smoothstep(.62f, .97f, t)
        } else sourceView.alpha = 1f

        // Tiny mid-flight emphasis gives mass without overshooting the actual container bounds.
        val lift = 1f + .006f * (1f - abs(t * 2f - 1f))
        sourceView.contentScale = lift
        destination?.contentScale = lift
    }

    private fun applyBox(view: MorphBitmapView, box: WindowBox, radiusPx: Float) {
        view.container = RectF(
            (box.x - display.x).toFloat(),
            (box.y - display.y).toFloat(),
            (box.x - display.x + box.width).toFloat(),
            (box.y - display.y + box.height).toFloat()
        )
        view.cornerRadius = radiusPx
    }

    private fun progressFor(box: WindowBox): Float {
        val total = abs(destinationBox.x - source.box.x) + abs(destinationBox.y - source.box.y) +
            abs(destinationBox.width - source.box.width) + abs(destinationBox.height - source.box.height)
        if (total == 0) return 1f
        val done = abs(box.x - source.box.x) + abs(box.y - source.box.y) +
            abs(box.width - source.box.width) + abs(box.height - source.box.height)
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun lerpRadius(t: Float) = lerp(sourceRadiusPx, destinationRadiusPx, t)

    /** Full-overlay custom draw: container moves, content scales uniformly and is clipped/cropped. */
    private class MorphBitmapView(context: Context, bitmap: Bitmap) : View(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        private val clipPath = Path()
        private val drawRect = RectF()
        private var _container = RectF()
        var container: RectF
            get() = RectF(_container)
            set(value) { _container.set(value); invalidate() }
        var cornerRadius: Float = 0f
            set(value) { if (field != value) { field = value; invalidate() } }
        var contentScale: Float = 1f
            set(value) { if (field != value) { field = value; invalidate() } }

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            val box = _container
            if (box.width() <= 0f || box.height() <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return

            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(box, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)

            // Center-crop with one scalar so glyphs, text and faces retain their exact aspect ratio.
            var scale = max(box.width() / bitmap.width.toFloat(), box.height() / bitmap.height.toFloat())
            scale *= contentScale
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            drawRect.set(
                box.centerX() - dw / 2f,
                box.centerY() - dh / 2f,
                box.centerX() + dw / 2f,
                box.centerY() + dh / 2f
            )
            canvas.drawBitmap(bitmap, null, drawRect, paint)
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
