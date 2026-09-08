package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max

internal data class MorphFrame(val bitmap: Bitmap, val box: WindowBox)

/**
 * Fullscreen -> floating visual bridge.
 *
 * There is exactly one moving object: a frozen fullscreen frame. The bitmap is uploaded once to a
 * hardware layer; the animation thereafter changes compositor translation/scale plus a clip rect.
 *
 * The clip rect is important. The fullscreen and floating cards have different aspect ratios. A
 * non-uniform X/Y scale makes text visibly squash while the card shrinks, which reads as a stretched
 * screenshot rather than one physical window changing shape. We now use one uniform "cover" scale
 * and progressively crop the frozen frame to the destination aspect ratio. The outer rectangle still
 * follows the exact floating geometry, but glyphs, icons and line weights keep their proportions.
 *
 * Two additional visual rules remain strict:
 *  1. The frozen fullscreen frame stays stationary and opaque briefly after Activity focus loss,
 *     because Android may report focus loss before SurfaceFlinger removes the Activity surface.
 *  2. There is never an alpha blend between the landed screenshot and the reflowed floating page.
 *     The real card becomes opaque behind the still-opaque snapshot for a full vsync, then the
 *     snapshot disappears on the next vsync. There is never a double-text frame or black gap.
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
                // screen-space geometry to where Android actually laid this overlay out.
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
     * then remove the screenshot on the next vsync. `crossfadeStart` remains only for call-site
     * compatibility; no alpha crossfade is performed.
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
                // The card starts square-edged like fullscreen and acquires its floating radius as
                // the outer window becomes visibly card-sized.
                val radiusT = smoothstep(.42f, 1f, t)
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

                    // First endpoint vsync: the final card becomes fully visible behind an opaque
                    // snapshot, so the TextureView has a complete correctly-sized frame ready.
                    liveDestination.postOnAnimation {
                        if (!attached || !liveDestination.isAttachedToWindow) return@postOnAnimation
                        liveDestination.alpha = 1f

                        // Second endpoint vsync: remove the snapshot atomically. No ghosted reflow.
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
        // frame pinned through that compositor transaction before beginning any visible movement.
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
     * Keep screenshot pixels geometrically correct while the outer card changes aspect ratio.
     *
     * A uniform cover scale is chosen for the requested screen-space box. The visible local region
     * is then center-cropped to exactly the required width/height. Translation includes the crop
     * offset, so the visible clip lands on `box` pixel-for-pixel even though the underlying View
     * remains source-sized and never relayouts during the animation.
     */
    private fun applyFrame(box: WindowBox, radiusPx: Float) {
        val sourceWidth = source.box.width.coerceAtLeast(1).toFloat()
        val sourceHeight = source.box.height.coerceAtLeast(1).toFloat()
        val requestedW = box.width.coerceAtLeast(1).toFloat()
        val requestedH = box.height.coerceAtLeast(1).toFloat()
        val scale = max(requestedW / sourceWidth, requestedH / sourceHeight).coerceAtLeast(.0001f)

        val visibleLocalW = (requestedW / scale).coerceAtMost(sourceWidth)
        val visibleLocalH = (requestedH / scale).coerceAtMost(sourceHeight)
        val left = ((sourceWidth - visibleLocalW) * .5f).coerceAtLeast(0f)
        val top = ((sourceHeight - visibleLocalH) * .5f).coerceAtLeast(0f)
        val right = (left + visibleLocalW).coerceAtMost(sourceWidth)
        val bottom = (top + visibleLocalH).coerceAtMost(sourceHeight)
        val clip = Rect(
            left.toInt(),
            top.toInt(),
            right.toInt().coerceAtLeast(left.toInt() + 1),
            bottom.toInt().coerceAtLeast(top.toInt() + 1)
        )

        frame.visibleClip = clip
        frame.translationX = box.x - rootScreenX - clip.left * scale
        frame.translationY = box.y - rootScreenY - clip.top * scale
        frame.scaleX = scale
        frame.scaleY = scale
        frame.localCornerRadius = radiusPx / scale
    }

    /** Bitmap is drawn once into a hardware layer; animation thereafter transforms/crops that layer. */
    private class SnapshotBitmapView(context: Context, bitmap: Bitmap) : View(context) {
        private var image: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        var visibleClip: Rect = Rect(0, 0, bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1))
            set(value) {
                if (field != value) {
                    field = Rect(value)
                    clipBounds = field
                    invalidateOutline()
                }
            }
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
                    val r = visibleClip
                    outline.setRoundRect(r.left, r.top, r.right.coerceAtLeast(r.left + 1), r.bottom.coerceAtLeast(r.top + 1), localCornerRadius)
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
