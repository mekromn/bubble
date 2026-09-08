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
 *
 * Two visual rules are deliberately strict here:
 *  1. The frozen fullscreen frame stays completely stationary and opaque briefly after the Activity
 *     loses focus. Android can revoke focus before SurfaceFlinger has actually removed the Activity,
 *     and beginning the shrink in that interval exposes two copies of the page.
 *  2. There is never an alpha crossfade between the landed screenshot and the reflowed floating
 *     webpage. Two different text layouts blended together are visible as ghosting even for 60 ms.
 *     Instead the live card is made fully opaque behind the still-opaque snapshot for one complete
 *     display frame, then the snapshot is removed on the following vsync. The user sees one image at
 *     every instant and there is no black gap or double-text frame.
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
     * Move only the frozen screenshot. The real destination stays warm and fixed underneath. Once
     * the screenshot reaches destinationBox, promote the real card behind it for a complete vsync,
     * then remove the screenshot on the next vsync. `crossfadeStart` remains in the API only so the
     * handoff caller does not need a compatibility-only change; no actual crossfade is performed.
     */
    @Suppress("UNUSED_PARAMETER")
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
            liveDestination.alpha = 1f
            frame.alpha = 0f
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

                    // First endpoint vsync: make the final card fully visible *behind* the opaque
                    // screenshot. The screenshot still covers every pixel, so this cannot ghost.
                    liveDestination.postOnAnimation {
                        if (!attached || !liveDestination.isAttachedToWindow) return@postOnAnimation
                        liveDestination.alpha = 1f

                        // Second endpoint vsync: the live TextureView has owned a complete frame at
                        // final geometry. Remove the snapshot atomically; no blended text and no gap.
                        liveDestination.postOnAnimation {
                            if (!attached || !liveDestination.isAttachedToWindow) return@postOnAnimation
                            frame.alpha = 0f
                            onProgress?.invoke(1f, 1f, destinationBox)
                            onEnd()
                        }
                    }
                }
            })
        }
        animator = motion

        // Focus loss happens earlier than visual task removal on Android 16. Keep the exact captured
        // fullscreen image pinned in place long enough for that compositor transaction to settle;
        // otherwise the first part of the shrink can reveal the still-visible Activity underneath.
        root.postDelayed({
            if (attached && animator === motion) motion.start()
        }, PRE_MOTION_HOLD_MS)
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
        private const val PRE_MOTION_HOLD_MS = 96L

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
