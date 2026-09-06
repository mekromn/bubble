package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Real system compositor blur for the floating overlay on Android 12+.
 *
 * FLAG_BLUR_BEHIND belongs to an entire WindowManager window. Putting it on the Gecko-containing
 * floating window can therefore blur compositor layers that belong to the page as well as the
 * transparent glass chrome. Bubble instead owns a second, non-interactive transparent backdrop
 * window. It is inserted immediately BEFORE the real floating window and carries the blur flag.
 * The real Bubble window is composited above it, so opaque Gecko pixels stay sharp and the blur is
 * visible only through Bubble's translucent/transparent glass regions.
 *
 * No screenshots, PixelCopy, bitmap caching or RenderEffect approximation are used.
 */
internal object OverlayGlass {
    private var owner: View? = null
    private var backdrop: View? = null
    private var backdropParams: WindowManager.LayoutParams? = null
    private var backdropManager: WindowManager? = null

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    /**
     * Called before the real overlay is first attached and whenever its geometry/flags change.
     * The supplied LayoutParams ALWAYS leave blur disabled on the real content window.
     */
    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return

        // Critical invariant: Gecko/content window itself never receives blur-behind.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.setBlurBehindRadius(0)

        val currentOwner = BubbleService.active?.window?.transitionView
        if (currentOwner != null) ensureBackdrop(context, manager, currentOwner)

        val view = backdrop ?: return
        val lp = backdropParams ?: return
        if (backdropManager !== manager) return
        val useBlur = enabled && manager.isCrossWindowBlurEnabled
        if (useBlur) {
            lp.x = params.x; lp.y = params.y
            lp.width = params.width.coerceAtLeast(1); lp.height = params.height.coerceAtLeast(1)
            lp.flags = baseFlags() or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            // ~20 physical px on a Pixel-class density. Bounded to keep the always-on-top surface cheap.
            lp.setBlurBehindRadius(Ui.dp(context, 6f).coerceIn(12, 28))
        } else {
            lp.x = params.x; lp.y = params.y
            lp.width = 1; lp.height = 1
            lp.flags = baseFlags()
            lp.setBlurBehindRadius(0)
        }
        try { manager.updateViewLayout(view, lp) } catch (_: RuntimeException) { release() }
    }

    private fun ensureBackdrop(context: Context, manager: WindowManager, currentOwner: View) {
        if (owner === currentOwner && backdrop != null && backdropManager === manager) return
        release()
        val view = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = 0
            title = "Bubble localized glass backdrop"
            setBlurBehindRadius(0)
        }
        try {
            // FloatingWindow calls us before manager.addView(root,...), so this backdrop is below
            // Gecko/native chrome in the compositor Z order from its first frame onward.
            manager.addView(view, lp)
            owner = currentOwner; backdrop = view; backdropParams = lp; backdropManager = manager
            currentOwner.addOnAttachStateChangeListener(detach)
        } catch (_: RuntimeException) {
            runCatching { manager.removeView(view) }
        }
    }

    private fun release() {
        val oldOwner = owner
        val view = backdrop
        val manager = backdropManager
        owner = null; backdrop = null; backdropParams = null; backdropManager = null
        oldOwner?.removeOnAttachStateChangeListener(detach)
        if (view != null && manager != null) runCatching { manager.removeView(view) }
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}
