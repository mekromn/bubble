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
 * Temporary compositor surface used only while the same browser window crosses the Activity /
 * TYPE_APPLICATION_OVERLAY boundary. It absorbs touch during the few transition frames so input
 * can never hit an invisible source/destination window while the shared card is in flight.
 *
 * The shared container owns geometry, clipping and surface opacity. Source/destination pixels are
 * uniformly center-cropped rather than independently X/Y-scaled, so text and icons never become
 * rubbery when the fullscreen and floating aspect ratios diverge. A matching backing surface also
 * morphs between opaque fullscreen chrome and translucent floating glass, preventing the launcher
 * from bleeding through during a fade-through while preserving the floating card's glass at rest.
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
    private val expanding = destinationBox.width > source.box.width || destinationBox.height > source.box.height
    private val root = FrameLayout(app).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = false
        setOnTouchListener { _, _ -> true }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val backdropView = MorphBackdropView(app, Ui.BG)
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
        root.addView(backdropView, FrameLayout.LayoutParams(-1, -1))
        root.addView(sourceView, FrameLayout.LayoutParams(-1, -1))
        applyBox(backdropView, source.box, sourceRadiusPx)
        applyBox(sourceView, source.box, sourceRadiusPx)
        applySurfaceOpacity(0f)
    }

    fun attach(onReady: () -> Unit = {}) {
        if (attached) { onReady(); return }
        // The transition surface itself gets the fastest real display mode. On the Pixel this is
        // essential: voting only the source/destination windows could let the temporary shared
        // element fall back to 60 Hz exactly while it is the only thing the user is watching.
        RenderPolicy.vote(app, root, params)
        manager.addView(root, params)
        attached = true
        root.postOnAnimation {
            if (attached) {
                RenderPolicy.vote(app, root)
                onReady()
            }
        }
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
        if (Build.VERSION.SDK_INT >= 35) RenderPolicy.vote(app, view)
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

    /** At full size, cover the held frame with a real PixelCopy of BrowserActivity at same bounds. */
    fun finishWith(frame: MorphFrame?, durationMs: Long = 105L, onEnd: () -> Unit) {
        if (!attached || frame == null || !ValueAnimator.areAnimatorsEnabled()) {
            onEnd(); return
        }
        setDestination(frame)
        val dest = destinationView ?: run { onEnd(); return }
        dest.alpha = 0f
        sourceView.alpha = 1f
        dest.animate().cancel(); sourceView.animate().cancel()
        // Source deliberately remains fully opaque underneath. Fading both layers at once creates
        // an avoidable opacity trough where the underlying Activity/launcher can leak through.
        dest.animate().withLayer().alpha(1f).setDuration(durationMs).setInterpolator(Ui.ease)
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
        applyBox(backdropView, current, radius)
        applyBox(sourceView, current, radius)
        destinationView?.let { applyBox(it, current, radius) }
        applySurfaceOpacity(t)

        // Fade-through happens *inside the same moving container*. The source stays opaque below
        // the destination for the entire transform, so combined opacity never falls below one.
        val destination = destinationView
        if (destination != null) {
            destination.alpha = smoothstep(.52f, .92f, t)
            sourceView.alpha = 1f
        } else sourceView.alpha = 1f

        // Tiny mid-flight content emphasis gives mass without overshooting the container bounds.
        val lift = 1f + .006f * (1f - abs(t * 2f - 1f))
        sourceView.contentScale = lift
        destination?.contentScale = lift
    }

    private fun applySurfaceOpacity(t: Float) {
        // Fullscreen's root is opaque while the floating card intentionally uses translucent glass.
        // Grow the opaque backing during expansion; remove it during contraction. This makes the
        // surface itself appear to materialize/dematerialize rather than exposing a foreign window.
        backdropView.surfaceAlpha = if (expanding) {
            smoothstep(.08f, .92f, t)
        } else {
            1f - smoothstep(.18f, .96f, t)
        }
    }

    private fun applyBox(view: MorphContainerView, box: WindowBox, radiusPx: Float) {
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

    private abstract class MorphContainerView(context: Context) : View(context) {
        private var _container = RectF()
        var container: RectF
            get() = RectF(_container)
            set(value) { _container.set(value); invalidate() }
        var cornerRadius: Float = 0f
            set(value) { if (field != value) { field = value; invalidate() } }
        protected val box: RectF get() = _container
    }

    private class MorphBackdropView(context: Context, color: Int) : MorphContainerView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var surfaceAlpha: Float = 0f
            set(value) { if (field != value) { field = value.coerceIn(0f, 1f); invalidate() } }
        override fun onDraw(canvas: Canvas) {
            val rect = box
            if (rect.width() <= 0f || rect.height() <= 0f || surfaceAlpha <= 0f) return
            paint.alpha = (surfaceAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }

    /** Full-overlay custom draw: container moves, content scales uniformly and is clipped/cropped. */
    private class MorphBitmapView(context: Context, bitmap: Bitmap) : MorphContainerView(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        private val clipPath = Path()
        private val drawRect = RectF()
        var contentScale: Float = 1f
            set(value) { if (field != value) { field = value; invalidate() } }

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            val rect = box
            if (rect.width() <= 0f || rect.height() <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return

            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)

            // Center-crop with one scalar so glyphs, text and faces retain their exact aspect ratio.
            var scale = max(rect.width() / bitmap.width.toFloat(), rect.height() / bitmap.height.toFloat())
            scale *= contentScale
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            drawRect.set(
                rect.centerX() - dw / 2f,
                rect.centerY() - dh / 2f,
                rect.centerX() + dw / 2f,
                rect.centerY() + dh / 2f
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
