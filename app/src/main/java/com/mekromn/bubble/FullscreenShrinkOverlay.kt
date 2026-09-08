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

internal data class MorphFrame(val bitmap: Bitmap, val box: WindowBox)

/**
 * Dedicated fullscreen -> floating transition layer.
 *
 * The live fullscreen window is first frozen into one exact frame. This layer then stretches that
 * single frame from the fullscreen rectangle into the already-saved floating rectangle. The real
 * floating window never moves during the transition; it waits underneath at its final bounds and
 * cross-fades in only during the last few frames while the frozen frame fades out at the exact same
 * geometry. That avoids Gecko reflow, SurfaceView/TextureView ownership flashes, and the visual
 * impression of two unrelated windows.
 */
internal class FullscreenShrinkOverlay(
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
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setOnTouchListener { _, _ -> true }
    }
    private val frame = StretchBitmapView(app, source.bitmap)
    private var attached = false
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
        title = "Bubble fullscreen shrink morph"
    }

    init {
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        applyFrame(source.box, sourceRadiusPx)
    }

    fun attach(onReady: () -> Unit) {
        if (attached) { onReady(); return }
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

    /**
     * Stretch the frozen fullscreen frame to the exact floating bounds. Cross-fade starts late so
     * almost the entire motion is literally one screenshot changing geometry. The live card is
     * already at destinationBox and therefore never jumps, settles, or changes position underneath.
     */
    fun morphInto(
        liveDestination: View,
        durationMs: Long = 320L,
        crossfadeStart: Float = .74f,
        onEnd: () -> Unit
    ) {
        animator?.cancel()
        liveDestination.animate().cancel()
        liveDestination.animate().withEndAction(null)
        liveDestination.alpha = 0f
        liveDestination.scaleX = 1f
        liveDestination.scaleY = 1f
        liveDestination.translationX = 0f
        liveDestination.translationY = 0f

        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyFrame(destinationBox, destinationRadiusPx)
            frame.alpha = 0f
            liveDestination.alpha = 1f
            onEnd()
            return
        }

        var cancelled = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ui.ease
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                val box = lerpBox(source.box, destinationBox, t)
                val radius = lerp(sourceRadiusPx, destinationRadiusPx, t)
                applyFrame(box, radius)

                val blend = smoothstep(crossfadeStart, 1f, t)
                frame.alpha = 1f - blend
                liveDestination.alpha = blend
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                    if (animator === animation) animator = null
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                    if (cancelled || !attached) return
                    applyFrame(destinationBox, destinationRadiusPx)
                    frame.alpha = 0f
                    liveDestination.alpha = 1f
                    onEnd()
                }
            })
            start()
        }
    }

    fun detach() {
        val running = animator
        animator = null
        running?.cancel()
        frame.animate().cancel()
        if (attached) {
            attached = false
            try { manager.removeViewImmediate(root) } catch (_: RuntimeException) { }
        }
        frame.release()
    }

    private fun applyFrame(box: WindowBox, radiusPx: Float) {
        frame.container = RectF(
            (box.x - display.x).toFloat(),
            (box.y - display.y).toFloat(),
            (box.x - display.x + box.width).toFloat(),
            (box.y - display.y + box.height).toFloat()
        )
        frame.cornerRadius = radiusPx
    }

    /** The bitmap is intentionally stretched to the moving rectangle, not center-cropped. */
    private class StretchBitmapView(context: Context, bitmap: Bitmap) : View(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        private val clipPath = Path()
        private var _container = RectF()
        var container: RectF
            get() = RectF(_container)
            set(value) { _container.set(value); invalidate() }
        var cornerRadius: Float = 0f
            set(value) { if (field != value) { field = value; invalidate() } }

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            if (_container.width() <= 0f || _container.height() <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(_container, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bitmap, null, _container, paint)
            canvas.restoreToCount(save)
        }

        fun release() {
            val bitmap = image
            image = null
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        private fun displayBox(context: Context): WindowBox {
            val manager = context.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= 30) {
                val bounds = manager.maximumWindowMetrics.bounds
                WindowBox(bounds.left, bounds.top, bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
            } else {
                val p = android.graphics.Point()
                @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
                WindowBox(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
            }
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun lerpInt(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()
        private fun lerpBox(a: WindowBox, b: WindowBox, t: Float) = WindowBox(
            lerpInt(a.x, b.x, t),
            lerpInt(a.y, b.y, t),
            lerpInt(a.width, b.width, t).coerceAtLeast(1),
            lerpInt(a.height, b.height, t).coerceAtLeast(1)
        )

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}