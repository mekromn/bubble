package com.mekromn.bubble

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.min

/**
 * Shape-clipped system compositor blur for Bubble overlays on Android 12+.
 *
 * The rendered browser page is never a blur target. Floating CHAT mode uses two small compositor
 * backdrops only: one behind the 52dp native header and one behind the 48dp native utility strip.
 * The Gecko SurfaceView between those strips stays on the direct compositor path with no blur
 * behind it. BUBBLE and CHOOSER are native UI, so they may still use a full-shape glass backdrop.
 *
 * The UI blur remains live while the floating window moves and resizes. Only geometry changes on
 * motion frames. Blur radius and shape state are cached, so drag/resize does not allocate new
 * drawables or repeatedly send identical compositor configuration.
 *
 * Two non-interactive backdrop windows are created before FloatingWindow adds its real overlay.
 * Keeping both alive (with the unused one collapsed to 1x1) preserves their Z-order underneath the
 * interactive window across CHAT/BUBBLE/CHOOSER transitions; we never create a new blur window above
 * an already-attached Gecko surface. No screenshots, bitmap caches, polling or RenderEffect tricks.
 */
internal object OverlayGlass {
    private enum class Shape { OFF, FULL, TOP, BOTTOM }

    private data class WindowState(
        var x: Int = Int.MIN_VALUE,
        var y: Int = Int.MIN_VALUE,
        var width: Int = -1,
        var height: Int = -1,
        var shape: Shape = Shape.OFF,
        var corner: Float = -1f,
        var blur: Int = -1
    )

    private var owner: View? = null
    private var primary: Dialog? = null
    private var secondary: Dialog? = null
    private var blurManager: WindowManager? = null
    private var blurListener: Consumer<Boolean>? = null
    private var primaryState = WindowState()
    private var secondaryState = WindowState()

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    /** Capability hint for tint/fallback choice. The requested radius remains configured either way. */
    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    /**
     * Existing call contract is retained. `expanded` distinguishes the resting bubble from full
     * native panels; the live FloatingWindow mode decides whether CHAT gets chrome-only blur.
     */
    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, expanded: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val floating = BubbleService.active?.window ?: return
        val currentOwner = floating.transitionView
        if (!ensureBackdrops(context, manager, currentOwner)) return

        if (floating.mode == FloatingMode.CHAT) updateChatChrome(context, params)
        else updateFull(context, params, expanded)
    }

    @SuppressLint("NewApi")
    private fun ensureBackdrops(context: Context, manager: WindowManager, currentOwner: View): Boolean {
        if (owner === currentOwner && primary?.isShowing == true && secondary?.isShowing == true) return true
        release()

        // FloatingWindow calls apply() before manager.addView(root, params) on first attachment.
        // Create both blur windows now so both remain below the interactive overlay forever.
        val first = createBackdrop(context, "Bubble glass primary") ?: return false
        val second = createBackdrop(context, "Bubble glass secondary") ?: run {
            runCatching { first.dismiss() }
            return false
        }
        owner = currentOwner
        primary = first
        secondary = second
        blurManager = manager
        primaryState = WindowState()
        secondaryState = WindowState()
        currentOwner.addOnAttachStateChangeListener(detach)
        registerBlurListener(manager, currentOwner)
        return true
    }

    @SuppressLint("NewApi")
    private fun createBackdrop(context: Context, title: String): Dialog? {
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
            this.title = title
        }
        window.setBackgroundBlurRadius(0)
        return try { dialog.show(); dialog }
        catch (_: RuntimeException) { runCatching { dialog.dismiss() }; null }
    }

    @SuppressLint("NewApi")
    private fun updateFull(context: Context, source: WindowManager.LayoutParams, expanded: Boolean) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val corner = if (expanded) Ui.dp(context, 26f).toFloat() else min(width, height) / 2f
        val blurRadius = if (expanded) Ui.dp(context, 18f).coerceIn(36, 72)
            else Ui.dp(context, 14f).coerceIn(28, 60)
        update(primary ?: return, primaryState, source.x, source.y, width, height, Shape.FULL, corner, blurRadius)
        disable(secondary, secondaryState, source.x, source.y)
    }

    @SuppressLint("NewApi")
    private fun updateChatChrome(context: Context, source: WindowManager.LayoutParams) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val topHeight = Ui.dp(context, 52f).coerceAtMost(height)
        val bottomHeight = Ui.dp(context, 48f).coerceAtMost((height - topHeight).coerceAtLeast(0))
        val corner = Ui.dp(context, 26f).toFloat()
        val blurRadius = Ui.dp(context, 18f).coerceIn(36, 72)

        // These two small native chrome windows follow every move/resize update. The Gecko page
        // region between them has no blur window underneath it at all.
        update(primary ?: return, primaryState, source.x, source.y, width, topHeight.coerceAtLeast(1), Shape.TOP, corner, blurRadius)
        if (bottomHeight > 0) {
            update(secondary ?: return, secondaryState, source.x, source.y + height - bottomHeight,
                width, bottomHeight, Shape.BOTTOM, corner, blurRadius)
        } else disable(secondary, secondaryState, source.x, source.y + height)
    }

    /**
     * Shape/radius changes are mode changes, not motion changes. Moving/resizing a live CHAT window
     * normally reaches only the geometry block below, avoiding drawable allocation and redundant
     * setBackgroundBlurRadius calls in the 120 Hz hot path.
     */
    @SuppressLint("NewApi")
    private fun update(dialog: Dialog, state: WindowState, x: Int, y: Int, width: Int, height: Int,
        shape: Shape, corner: Float, blurRadius: Int) {
        val window = dialog.window ?: return
        if (state.shape != shape || state.corner != corner) {
            window.setBackgroundDrawable(glassShape(shape, corner))
            state.shape = shape
            state.corner = corner
        }
        if (state.blur != blurRadius) {
            window.setBackgroundBlurRadius(blurRadius)
            state.blur = blurRadius
        }

        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (state.x == x && state.y == y && state.width == w && state.height == h) return
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y
            this.width = w; this.height = h
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        state.x = x; state.y = y; state.width = w; state.height = h
    }

    @SuppressLint("NewApi")
    private fun disable(dialog: Dialog?, state: WindowState, x: Int, y: Int) {
        val window = dialog?.window ?: return
        if (state.shape != Shape.OFF) {
            window.setBackgroundDrawable(transparentDrawable())
            state.shape = Shape.OFF
            state.corner = 0f
        }
        if (state.blur != 0) {
            window.setBackgroundBlurRadius(0)
            state.blur = 0
        }
        if (state.x == x && state.y == y && state.width == 1 && state.height == 1) return
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y; width = 1; height = 1
            format = PixelFormat.TRANSLUCENT; dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        state.x = x; state.y = y; state.width = 1; state.height = 1
    }

    private fun glassShape(shape: Shape, corner: Float) = GradientDrawable().apply {
        this.shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        cornerRadii = when (shape) {
            Shape.TOP -> floatArrayOf(corner,corner, corner,corner, 0f,0f, 0f,0f)
            Shape.BOTTOM -> floatArrayOf(0f,0f, 0f,0f, corner,corner, corner,corner)
            Shape.FULL -> floatArrayOf(corner,corner, corner,corner, corner,corner, corner,corner)
            Shape.OFF -> floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)
        }
    }

    private fun transparentDrawable() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

    @SuppressLint("NewApi")
    private fun registerBlurListener(manager: WindowManager, currentOwner: View) {
        val listener = Consumer<Boolean> {
            currentOwner.post { Workspace.peek()?.changed() }
        }
        blurListener = listener
        runCatching { manager.addCrossWindowBlurEnabledListener(listener) }
    }

    @SuppressLint("NewApi")
    private fun release() {
        val oldOwner = owner
        val first = primary
        val second = secondary
        val manager = blurManager
        val listener = blurListener
        owner = null
        primary = null
        secondary = null
        blurManager = null
        blurListener = null
        primaryState = WindowState()
        secondaryState = WindowState()
        oldOwner?.removeOnAttachStateChangeListener(detach)
        if (manager != null && listener != null) runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
        if (first != null) runCatching { first.dismiss() }
        if (second != null) runCatching { second.dismiss() }
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}
