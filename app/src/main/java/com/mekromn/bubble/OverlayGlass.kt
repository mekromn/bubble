package com.mekromn.bubble

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.min

/**
 * One shape-clipped system-compositor blur window, matching the low-layer-count architecture of the
 * known-fast Build 84 while keeping browser pixels visually blur-free.
 *
 * BUBBLE/CHOOSER use the normal rounded full background. CHAT keeps the same single backdrop window
 * but its background is only a top 52dp strip plus bottom 48dp strip. The middle of the drawable is
 * fully transparent, and the top-Z Gecko SurfaceView covers the browser rectangle above this backdrop.
 * This avoids the two extra service-owned blur windows introduced after Build 84 while preserving live
 * glass on native chrome during every move/resize frame.
 *
 * The backdrop is a real independent Window, so it receives the same maximum-refresh policy as the
 * interactive chrome and Gecko page. Otherwise Android's frame-rate arbitration could treat this live
 * blur layer as a normal-rate participant while the page is asking for high refresh.
 */
internal object OverlayGlass {
    private enum class Shape { OFF, FULL, CHROME }

    private data class WindowState(
        var x: Int = Int.MIN_VALUE,
        var y: Int = Int.MIN_VALUE,
        var width: Int = -1,
        var height: Int = -1,
        var shape: Shape = Shape.OFF,
        var corner: Float = -1f,
        var top: Int = -1,
        var bottom: Int = -1,
        var blur: Int = -1
    )

    private var owner: View? = null
    private var backdrop: Dialog? = null
    private var blurManager: WindowManager? = null
    private var blurListener: Consumer<Boolean>? = null
    private var state = WindowState()

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, expanded: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val floating = BubbleService.active?.window ?: return
        val currentOwner = floating.transitionView
        val glass = ensureBackdrop(context, manager, currentOwner) ?: return
        if (floating.mode == FloatingMode.CHAT) updateChat(context, glass, params)
        else updateFull(context, glass, params, expanded)
    }

    @SuppressLint("NewApi")
    private fun ensureBackdrop(context: Context, manager: WindowManager, currentOwner: View): Dialog? {
        backdrop?.takeIf { owner === currentOwner && it.isShowing }?.let { return it }
        release()

        val dialog = Dialog(context, R.style.Theme_Bubble_GlassOverlay)
        val window = dialog.window ?: return null
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(baseFlags())
        window.setDimAmount(0f)
        window.setBackgroundDrawable(transparentDrawable())
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0; y = 0; width = 1; height = 1
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            title = "Bubble single masked glass backdrop"
        }
        window.setBackgroundBlurRadius(0)

        return try {
            // FloatingWindow calls apply() before its interactive overlay is attached, preserving
            // stable same-type Z order below the Gecko/native window for the lifetime of the panel.
            dialog.show()
            // This is a separate compositor Window. Give it the same max-refresh request as the page
            // and native chrome so live blur does not become the low-rate participant in the scene.
            val attributes = window.attributes
            RenderPolicy.vote(context, window.decorView, attributes)
            window.attributes = attributes
            owner = currentOwner
            backdrop = dialog
            blurManager = manager
            state = WindowState()
            currentOwner.addOnAttachStateChangeListener(detach)
            registerBlurListener(manager, currentOwner)
            dialog
        } catch (_: RuntimeException) {
            runCatching { dialog.dismiss() }
            null
        }
    }

    @SuppressLint("NewApi")
    private fun updateFull(context: Context, dialog: Dialog, source: WindowManager.LayoutParams, expanded: Boolean) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val corner = if (expanded) Ui.dp(context, 26f).toFloat() else min(width, height) / 2f
        val blurRadius = if (expanded) Ui.dp(context, 18f).coerceIn(36, 72)
            else Ui.dp(context, 14f).coerceIn(28, 60)
        update(dialog, source, Shape.FULL, corner, 0, 0, blurRadius)
    }

    @SuppressLint("NewApi")
    private fun updateChat(context: Context, dialog: Dialog, source: WindowManager.LayoutParams) {
        val height = source.height.coerceAtLeast(1)
        val top = Ui.dp(context, 52f).coerceAtMost(height)
        val bottom = Ui.dp(context, 48f).coerceAtMost((height - top).coerceAtLeast(0))
        val corner = Ui.dp(context, 26f).toFloat()
        val blurRadius = Ui.dp(context, 18f).coerceIn(36, 72)
        update(dialog, source, Shape.CHROME, corner, top, bottom, blurRadius)
    }

    /** One compositor window; motion frames normally update geometry only. */
    @SuppressLint("NewApi")
    private fun update(dialog: Dialog, source: WindowManager.LayoutParams, shape: Shape,
        corner: Float, top: Int, bottom: Int, blurRadius: Int) {
        val window = dialog.window ?: return
        if (state.shape != shape || state.corner != corner || state.top != top || state.bottom != bottom) {
            window.setBackgroundDrawable(when (shape) {
                Shape.FULL -> glassShape(corner)
                Shape.CHROME -> ChromeMaskDrawable(top, bottom, corner)
                Shape.OFF -> transparentDrawable()
            })
            state.shape = shape
            state.corner = corner
            state.top = top
            state.bottom = bottom
        }
        if (state.blur != blurRadius) {
            window.setBackgroundBlurRadius(blurRadius)
            state.blur = blurRadius
        }

        val x = source.x
        val y = source.y
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        if (state.x == x && state.y == y && state.width == width && state.height == height) return
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y
            this.width = width; this.height = height
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        state.x = x; state.y = y; state.width = width; state.height = height
    }

    private class ChromeMaskDrawable(private val topHeight: Int, private val bottomHeight: Int,
        private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x01000000 }
        private val topPath = Path()
        private val bottomPath = Path()

        override fun onBoundsChange(bounds: Rect) {
            topPath.reset(); bottomPath.reset()
            val left = bounds.left.toFloat(); val right = bounds.right.toFloat()
            val top = bounds.top.toFloat(); val bottom = bounds.bottom.toFloat()
            val th = topHeight.coerceAtMost(bounds.height()).toFloat()
            val bh = bottomHeight.coerceAtMost((bounds.height() - th.toInt()).coerceAtLeast(0)).toFloat()
            if (th > 0f) {
                topPath.addRoundRect(RectF(left, top, right, top + th),
                    floatArrayOf(radius,radius, radius,radius, 0f,0f, 0f,0f), Path.Direction.CW)
            }
            if (bh > 0f) {
                bottomPath.addRoundRect(RectF(left, bottom - bh, right, bottom),
                    floatArrayOf(0f,0f, 0f,0f, radius,radius, radius,radius), Path.Direction.CW)
            }
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(topPath, paint)
            canvas.drawPath(bottomPath, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = if (alpha == 0) 0 else 1 }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun glassShape(corner: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        cornerRadius = corner
    }
    private fun transparentDrawable() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

    @SuppressLint("NewApi")
    private fun registerBlurListener(manager: WindowManager, currentOwner: View) {
        val listener = Consumer<Boolean> { currentOwner.post { Workspace.peek()?.changed() } }
        blurListener = listener
        runCatching { manager.addCrossWindowBlurEnabledListener(listener) }
    }

    @SuppressLint("NewApi")
    private fun release() {
        val oldOwner = owner
        val dialog = backdrop
        val manager = blurManager
        val listener = blurListener
        owner = null
        backdrop = null
        blurManager = null
        blurListener = null
        state = WindowState()
        oldOwner?.removeOnAttachStateChangeListener(detach)
        if (manager != null && listener != null) runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
        if (dialog != null) runCatching { dialog.dismiss() }
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}
