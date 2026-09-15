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
 * Cross-window blur with no bitmap/readback path and no idle animation.
 *
 * The old CHAT implementation used one full-panel Window whose drawable merely painted the middle
 * transparent. Android background blur is a compositor blur *region* based on the Window background
 * bounds/outline; a transparent hole drawn by an arbitrary Drawable does not split that region.
 * That meant SurfaceFlinger could blur the whole chat rectangle even though Gecko immediately covered
 * almost all of it.
 *
 * This implementation keeps the exact blur radius and chrome appearance, but gives SurfaceFlinger the
 * smallest honest geometry:
 *   - BUBBLE: one 64dp-ish blur region.
 *   - CHOOSER: one full-panel blur region because the whole chooser is glass.
 *   - CHAT: two narrow regions, exactly the 52dp top chrome and 48dp bottom chrome.
 *
 * Two Dialog windows are created before the interactive overlay so they permanently remain below the
 * page/chrome in same-type Z order. The second one parks at 1x1 with radius 0 unless CHAT needs it.
 * Blur availability is listener-driven and cached; normal Workspace renders never poll WindowManager.
 * Blur-only windows intentionally cast no frame-rate vote: they produce no app frames and should not
 * keep the display in a high-refresh mode by themselves.
 *
 * Global opaque mode is stronger: it removes these blur-only windows and their platform listener
 * entirely, so SurfaceFlinger has no Bubble background-blur work to perform.
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

    private data class BlurWindow(
        val dialog: Dialog,
        var state: WindowState = WindowState()
    )

    private var owner: View? = null
    private var primary: BlurWindow? = null
    private var secondary: BlurWindow? = null
    private var blurManager: WindowManager? = null
    private var blurListener: Consumer<Boolean>? = null
    private var blurEnabled: Boolean? = null

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    /** Cached after apply() registers the platform listener. */
    fun available(manager: WindowManager): Boolean {
        if (!VisualEffects.transparencyEnabled()) return false
        if (Build.VERSION.SDK_INT < 31) return false
        if (blurManager === manager) blurEnabled?.let { return it }
        return runCatching { manager.isCrossWindowBlurEnabled }.getOrDefault(false)
    }

    /**
     * Applies exact live-blur geometry and returns the platform's current blur-enabled state.
     * Calling this with identical geometry is a no-op: no WindowManager relayout is emitted.
     */
    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, expanded: Boolean): Boolean {
        if (!VisualEffects.transparencyEnabled()) {
            release()
            return false
        }
        if (Build.VERSION.SDK_INT < 31) return false
        val floating = BubbleService.active?.window ?: return available(manager)
        val currentOwner = floating.transitionView
        if (!ensureWindows(context, manager, currentOwner)) return available(manager)
        val enabled = blurEnabled ?: available(manager)
        if (!enabled) {
            park(primary)
            park(secondary)
            return false
        }
        when (floating.mode) {
            FloatingMode.CHAT -> updateChat(context, params)
            FloatingMode.CHOOSER -> updateFull(context, params, expanded = true)
            FloatingMode.BUBBLE -> updateFull(context, params, expanded = false)
        }
        return true
    }

    @SuppressLint("NewApi")
    private fun ensureWindows(context: Context, manager: WindowManager, currentOwner: View): Boolean {
        if (owner === currentOwner && primary?.dialog?.isShowing == true && secondary?.dialog?.isShowing == true) {
            return true
        }
        release()

        // Both are created now, before FloatingWindow adds its interactive root. This guarantees that
        // a later BUBBLE/CHOOSER -> CHAT transition never has to add a new same-type overlay above it.
        val first = createBackdrop(context) ?: return false
        val second = createBackdrop(context) ?: run {
            runCatching { first.dismiss() }
            return false
        }
        return try {
            first.show()
            second.show()
            owner = currentOwner
            primary = BlurWindow(first)
            secondary = BlurWindow(second)
            blurManager = manager
            blurEnabled = runCatching { manager.isCrossWindowBlurEnabled }.getOrDefault(false)
            currentOwner.addOnAttachStateChangeListener(detach)
            registerBlurListener(manager, currentOwner)
            // Park until apply() below supplies the real geometry.
            park(primary)
            park(secondary)
            true
        } catch (_: RuntimeException) {
            runCatching { first.dismiss() }
            runCatching { second.dismiss() }
            owner = null
            primary = null
            secondary = null
            blurManager = null
            blurEnabled = null
            false
        }
    }

    @SuppressLint("NewApi")
    private fun createBackdrop(context: Context): Dialog? {
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
            x = 0
            y = 0
            width = 1
            height = 1
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            title = "Bubble bounded glass backdrop"
        }
        window.setBackgroundBlurRadius(0)
        return dialog
    }

    @SuppressLint("NewApi")
    private fun updateFull(context: Context, source: WindowManager.LayoutParams, expanded: Boolean) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val corner = if (expanded) Ui.dp(context, 26f).toFloat() else min(width, height) / 2f
        val blurRadius = if (expanded) Ui.dp(context, 18f).coerceIn(36, 72)
            else Ui.dp(context, 14f).coerceIn(28, 60)
        update(primary, source.x, source.y, width, height, Shape.FULL, corner, blurRadius)
        park(secondary)
    }

    @SuppressLint("NewApi")
    private fun updateChat(context: Context, source: WindowManager.LayoutParams) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val top = Ui.dp(context, 52f).coerceAtMost(height)
        val bottom = Ui.dp(context, 48f).coerceAtMost((height - top).coerceAtLeast(0))
        val corner = Ui.dp(context, 26f).toFloat()
        val blurRadius = Ui.dp(context, 18f).coerceIn(36, 72)

        // Exact visible glass only. The Gecko page owns the large middle rectangle, so asking SF to
        // blur that area wastes work without changing a single visible pixel.
        if (top > 0) update(primary, source.x, source.y, width, top, Shape.TOP, corner, blurRadius)
        else park(primary)
        if (bottom > 0) {
            update(secondary, source.x, source.y + height - bottom, width, bottom, Shape.BOTTOM, corner, blurRadius)
        } else park(secondary)
    }

    @SuppressLint("NewApi")
    private fun update(target: BlurWindow?, x: Int, y: Int, width: Int, height: Int,
        shape: Shape, corner: Float, blurRadius: Int) {
        val holder = target ?: return
        val window = holder.dialog.window ?: return
        val state = holder.state
        if (state.shape != shape || state.corner != corner) {
            window.setBackgroundDrawable(when (shape) {
                Shape.FULL -> glassShape(corner)
                Shape.TOP -> stripShape(corner, top = true)
                Shape.BOTTOM -> stripShape(corner, top = false)
                Shape.OFF -> transparentDrawable()
            })
            state.shape = shape
            state.corner = corner
        }
        if (state.blur != blurRadius) {
            window.setBackgroundBlurRadius(blurRadius)
            state.blur = blurRadius
        }
        if (state.x == x && state.y == y && state.width == width && state.height == height) return
        val attributes = window.attributes
        attributes.gravity = Gravity.TOP or Gravity.LEFT
        attributes.x = x
        attributes.y = y
        attributes.width = width
        attributes.height = height
        attributes.format = PixelFormat.TRANSLUCENT
        attributes.dimAmount = 0f
        attributes.flags = (attributes.flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = attributes
        state.x = x
        state.y = y
        state.width = width
        state.height = height
    }

    @SuppressLint("NewApi")
    private fun park(target: BlurWindow?) {
        val holder = target ?: return
        val window = holder.dialog.window ?: return
        val state = holder.state
        if (state.shape != Shape.OFF) {
            window.setBackgroundDrawable(transparentDrawable())
            state.shape = Shape.OFF
            state.corner = 0f
        }
        if (state.blur != 0) {
            window.setBackgroundBlurRadius(0)
            state.blur = 0
        }
        if (state.width == 1 && state.height == 1 && state.x == 0 && state.y == 0) return
        val attributes = window.attributes
        attributes.gravity = Gravity.TOP or Gravity.LEFT
        attributes.x = 0
        attributes.y = 0
        attributes.width = 1
        attributes.height = 1
        attributes.format = PixelFormat.TRANSLUCENT
        attributes.dimAmount = 0f
        attributes.flags = (attributes.flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = attributes
        state.x = 0
        state.y = 0
        state.width = 1
        state.height = 1
    }

    private fun glassShape(corner: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        cornerRadius = corner
    }

    private fun stripShape(corner: Float, top: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        cornerRadii = if (top) {
            floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner)
        }
    }

    private fun transparentDrawable() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

    @SuppressLint("NewApi")
    private fun registerBlurListener(manager: WindowManager, currentOwner: View) {
        val listener = Consumer<Boolean> { enabled ->
            currentOwner.post {
                if (owner !== currentOwner) return@post
                if (blurEnabled == enabled) return@post
                blurEnabled = enabled
                if (!enabled) {
                    park(primary)
                    park(secondary)
                }
                // Update only the floating glass material. Do not wake every Workspace listener.
                BubbleService.active?.window?.crossWindowBlurChanged(enabled)
            }
        }
        blurListener = listener
        runCatching { manager.addCrossWindowBlurEnabledListener(listener) }
    }

    @SuppressLint("NewApi")
    private fun release() {
        val oldOwner = owner
        val manager = blurManager
        val listener = blurListener
        val first = primary?.dialog
        val second = secondary?.dialog
        owner = null
        primary = null
        secondary = null
        blurManager = null
        blurListener = null
        blurEnabled = null
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
