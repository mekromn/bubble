package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.sqrt

internal data class MorphFrame(val bitmap: Bitmap, val box: WindowBox)

/**
 * Fullscreen -> floating visual bridge.
 *
 * There is exactly one moving object: a frozen fullscreen frame. Its bitmap is uploaded once to a
 * hardware layer, then the compositor changes only translation/scale while it travels to the saved
 * floating rectangle. The live floating card is already laid out underneath at its final geometry.
 * It does NOT fade in while the screenshot is still moving: that was the ghost/double-image seen on
 * the Pixel because the floating Gecko viewport has already reflowed to a different shape. Only
 * after the snapshot has landed do we perform a very short stationary dissolve into the live card.
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
    private val frame = SnapshotBitmapView(app, source.bitmap).apply {
        pivotX = 0f
        pivotY = 0f
    }
    private var attached = false
    private var animator: ValueAnimator? = null
    private var currentBox = source.box
    private var rootScreenX = display.x
    private var rootScreenY = display.y
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
        root.addView(
            frame,
            FrameLayout.LayoutParams(source.box.width.coerceAtLeast(1), source.box.height.coerceAtLeast(1))
        )
        applyFrame(source.box, sourceRadiusPx)
    }

    fun attach(onReady: () -> Unit) {
        if (attached) { onReady(); return }
        RenderPolicy.vote(app, root, params)
        manager.addView(root, params)
        attached = true
        root.postOnAnimation {
            if (attached) {
                // OEM WindowManager implementations may shift TYPE_APPLICATION_OVERLAY. Anchor the
                // screen-space geometry to where Android really laid this overlay out.
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                rootScreenX = location[0]
                rootScreenY = location[1]
                applyFrame(currentBox, sourceRadiusPx)
                RenderPolicy.vote(app, root)
                onReady()
            }
        }
    }

    /**
     * Move the frozen fullscreen frame first. Once it is pixel-aligned with the floating rectangle,
     * hold that exact geometry for one display frame and dissolve to the already-rendering live card.
     * `crossfadeStart` is retained for source compatibility; it now only derives the very short
     * endpoint handoff duration instead of starting a blend while geometry is still changing.
     */
    fun morphInto(
        liveDestination: View,
        durationMs: Long = 320L,
        crossfadeStart: Float = .74f,
        onProgress: ((progress: Float, liveBlend: Float, box: WindowBox) -> Unit)? = null,
        onEnd: () -> Unit
    ) {
        animator?.cancel()
        liveDestination.animate().cancel()
        liveDestination.animate().withEndAction(null)
        // Tiny non-zero alpha keeps TextureView/Gecko composition warm underneath the opaque
        // snapshot without becoming perceptible to the user.
        liveDestination.alpha = LIVE_WARM_ALPHA
        liveDestination.scaleX = 1f
        liveDestination.scaleY = 1f
        liveDestination.translationX = 0f
        liveDestination.translationY = 0f
        frame.alpha = 1f

        if (!ValueAnimator.areAnimatorsEnabled()) {
            currentBox = destinationBox
            applyFrame(destinationBox, destinationRadiusPx)
            frame.alpha = 0f
            liveDestination.alpha = 1f
            onProgress?.invoke(1f, 1f, destinationBox)
            onEnd()
            return
        }

        var cancelled = false
        val motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ui.ease
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                val box = lerpBox(source.box, destinationBox, t)
                currentBox = box
                // Keep the source square while it is still visually fullscreen, then introduce the
                // floating card radius during the latter half of the travel.
                val radiusT = smoothstep(.52f, 1f, t)
                val radius = lerp(sourceRadiusPx, destinationRadiusPx, radiusT)
                applyFrame(box, radius)
                frame.alpha = 1f
                liveDestination.alpha = LIVE_WARM_ALPHA
                onProgress?.invoke(t, 0f, box)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                    if (animator === animation) animator = null
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                    if (cancelled || !attached) return
                    currentBox = destinationBox
                    applyFrame(destinationBox, destinationRadiusPx)
                    frame.alpha = 1f
                    liveDestination.alpha = LIVE_WARM_ALPHA
                    onProgress?.invoke(1f, 0f, destinationBox)

                    // One real display frame at identical geometry prevents the handoff from racing
                    // the TextureView resize/paint on 120 Hz Pixels.
                    liveDestination.postOnAnimation {
                        if (!attached || !liveDestination.isAttachedToWindow) return@postOnAnimation
                        startEndpointDissolve(
                            liveDestination,
                            handoffDurationMs(crossfadeStart),
                            onProgress,
                            onEnd
                        )
                    }
                }
            })
        }
        animator = motion
        motion.start()
    }

    private fun startEndpointDissolve(
        liveDestination: View,
        durationMs: Long,
        onProgress: ((progress: Float, liveBlend: Float, box: WindowBox) -> Unit)?,
        onEnd: () -> Unit
    ) {
        var cancelled = false
        val handoff = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Ui.ease
            addUpdateListener { value ->
                val blend = value.animatedValue as Float
                frame.alpha = 1f - blend
                liveDestination.alpha = LIVE_WARM_ALPHA + (1f - LIVE_WARM_ALPHA) * blend
                onProgress?.invoke(1f, blend, destinationBox)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                    if (animator === animation) animator = null
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                    if (cancelled || !attached) return
                    frame.alpha = 0f
                    liveDestination.alpha = 1f
                    onProgress?.invoke(1f, 1f, destinationBox)
                    onEnd()
                }
            })
        }
        animator = handoff
        handoff.start()
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

    /**
     * Geometry is compositor-only: the bitmap View never changes layout size after attach and its
     * content is not redrawn for every animation frame. Translation + non-uniform scale stretch the
     * one cached texture exactly from source rectangle to destination rectangle.
     */
    private fun applyFrame(box: WindowBox, radiusPx: Float) {
        val sourceWidth = source.box.width.coerceAtLeast(1).toFloat()
        val sourceHeight = source.box.height.coerceAtLeast(1).toFloat()
        val sx = box.width.coerceAtLeast(1) / sourceWidth
        val sy = box.height.coerceAtLeast(1) / sourceHeight
        frame.translationX = (box.x - rootScreenX).toFloat()
        frame.translationY = (box.y - rootScreenY).toFloat()
        frame.scaleX = sx
        frame.scaleY = sy
        // Outline lives in local coordinates. Geometric mean keeps the visible corner radius close
        // to the requested screen-space radius even when width and height scale by different amounts.
        val scale = sqrt((sx * sy).coerceAtLeast(.0001f))
        frame.localCornerRadius = radiusPx / scale
    }

    /** Bitmap is drawn once into a hardware layer; animation thereafter transforms that layer. */
    private class SnapshotBitmapView(context: Context, bitmap: Bitmap) : View(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        var localCornerRadius: Float = 0f
            set(value) {
                val next = value.coerceAtLeast(0f)
                if (kotlin.math.abs(field - next) > .25f) {
                    field = next
                    invalidateOutline()
                }
            }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), localCornerRadius)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(0, 0, width, height),
                paint
            )
        }

        fun release() {
            val bitmap = image
            image = null
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        private const val LIVE_WARM_ALPHA = .001f

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

        private fun handoffDurationMs(crossfadeStart: Float): Long =
            ((1f - crossfadeStart.coerceIn(.60f, .90f)) * 240f).toLong().coerceIn(64L, 88L)

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
