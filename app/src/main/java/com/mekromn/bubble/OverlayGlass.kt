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
 * IMPORTANT: Bubble intentionally does NOT use FLAG_BLUR_BEHIND / setBlurBehindRadius here.
 * Android defines that API as blurring the whole screen behind a window, which produced visible
 * blur outside Bubble on the target Pixel. The previous strip/backdrop approximation therefore
 * could never provide a hard visual boundary.
 *
 * Instead a separate non-interactive floating Dialog is inserted immediately below Bubble and uses
 * Window.setBackgroundBlurRadius(). Android clips background blur to the window background and its
 * rounded corners. The 64dp resting bubble uses a true circular rounded background; expanded
 * chooser/chat uses the same rounded rectangle geometry as Bubble. The Gecko/native content window
 * itself remains blur-free and opaque page pixels therefore stay sharp.
 *
 * The backdrop window follows Bubble during every move/resize/animation update. Its background-blur
 * radius stays configured even when the platform temporarily disables cross-window blur, so there
 * is no "only blur when motion stops" behavior. No screenshots, PixelCopy, bitmap caches, polling,
 * or RenderEffect approximation are used.
 */
internal object OverlayGlass {
    private var owner: View? = null
    private var backdrop: Dialog? = null
    private var blurManager: WindowManager? = null
    private var blurListener: Consumer<Boolean>? = null

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    /** Capability hint for tint/fallback choice. The requested radius remains configured either way. */
    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    /** `expanded=true` means chooser/chat; false is the 64dp resting bubble. */
    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, expanded: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val currentOwner = BubbleService.active?.window?.transitionView ?: return
        val glass = ensureBackdrop(context, manager, currentOwner) ?: return
        updateBackdrop(context, glass, params, expanded)
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

        // TYPE_APPLICATION_OVERLAY keeps this service-owned glass available over other apps. It is
        // deliberately non-focusable/non-touchable: all interaction remains in FloatingWindow.
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(baseFlags())
        window.setDimAmount(0f)
        window.setBackgroundDrawable(glassShape(1f))
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0; y = 0; width = 1; height = 1
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            title = "Bubble shape-clipped glass backdrop"
        }
        // Keep the API armed from the first compositor frame. A non-zero shape/tiny alpha ensures
        // Android has an outline from which to derive the exact blur bounds/corners.
        window.setBackgroundBlurRadius(1)

        return try {
            // FloatingWindow calls apply() before adding its real root, so this window is below the
            // interactive Gecko/native overlay in same-type Z order.
            dialog.show()
            owner = currentOwner
            backdrop = dialog
            blurManager = manager
            currentOwner.addOnAttachStateChangeListener(detach)
            registerBlurListener(manager, currentOwner)
            dialog
        } catch (_: RuntimeException) {
            runCatching { dialog.dismiss() }
            null
        }
    }

    @SuppressLint("NewApi")
    private fun updateBackdrop(context: Context, dialog: Dialog, source: WindowManager.LayoutParams, expanded: Boolean) {
        val window = dialog.window ?: return
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val corner = if (expanded) Ui.dp(context, 26f).toFloat() else min(width, height) / 2f
        val blurRadius = if (expanded) Ui.dp(context, 18f).coerceIn(36, 72)
            else Ui.dp(context, 14f).coerceIn(28, 60)

        // Background blur is clipped by Android to this drawable's bounds and rounded corners.
        // For the 64x64 bubble, radius == half the side, producing a true circular blur region.
        window.setBackgroundDrawable(glassShape(corner))
        window.setBackgroundBlurRadius(blurRadius)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = source.x; y = source.y
            this.width = width; this.height = height
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
    }

    /**
     * The fill is effectively invisible but non-zero so the background drawable retains a concrete
     * outline. Bubble's real tint/rim/sheen remains in GlassBubble/Ui; this window contributes blur.
     */
    private fun glassShape(cornerRadius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        this.cornerRadius = cornerRadius
    }

    @SuppressLint("NewApi")
    private fun registerBlurListener(manager: WindowManager, currentOwner: View) {
        val listener = Consumer<Boolean> {
            // Cross-window blur can be toggled by Android at runtime. The radius remains configured;
            // this callback only asks Bubble to refresh its translucent fallback/tint immediately.
            currentOwner.post { Workspace.peek()?.changed() }
        }
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
