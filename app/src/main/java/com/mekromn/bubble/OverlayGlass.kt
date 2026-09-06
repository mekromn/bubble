package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Real system compositor blur for Bubble overlays on Android 12+.
 *
 * The Gecko/native content window NEVER carries FLAG_BLUR_BEHIND. Blur lives in separate,
 * non-touchable windows immediately below it, so opaque webpage pixels remain sharp.
 *
 * Expanded chooser/chat uses one rectangular backdrop matching the panel. The resting 64dp bubble
 * uses three narrow blur strips whose union is fully inside the bubble's circular glass body. This
 * avoids the ugly square/rectangular blur halo that a normal blur-behind window would create around
 * a circular bubble while still giving the bubble itself genuine compositor-frosted glass.
 *
 * Blur remains continuously requested while moving, resizing and animating. If Android temporarily
 * disables cross-window blur, the flags/radii stay armed so the compositor can resume immediately
 * without waiting for motion to stop or another UI event. No screenshots, PixelCopy, bitmap cache,
 * idle polling or RenderEffect approximation are used.
 */
internal object OverlayGlass {
    private const val POOL = 3
    private var owner: View? = null
    private val backdrops = ArrayList<View>(POOL)
    private val backdropParams = ArrayList<WindowManager.LayoutParams>(POOL)
    private var backdropManager: WindowManager? = null

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (owner === v) release()
        }
    }

    /** Capability hint for tint choice only. An active blur request is never dropped mid-motion. */
    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    /**
     * `expanded=true` means chooser/chat. `false` means the resting bubble.
     * Called before the real overlay is first attached and before every geometry update.
     */
    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, expanded: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return

        // Critical invariant: Gecko/content and the interactive bubble window itself stay blur-free.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.setBlurBehindRadius(0)

        val currentOwner = BubbleService.active?.window?.transitionView
        if (currentOwner != null) ensureBackdrops(context, manager, currentOwner)
        if (backdropManager !== manager || backdrops.size != POOL) return

        if (expanded) configureExpanded(context, params) else configureBubble(context, params)
    }

    private fun configureExpanded(context: Context, source: WindowManager.LayoutParams) {
        configure(0, source.x, source.y, source.width.coerceAtLeast(1), source.height.coerceAtLeast(1),
            Ui.dp(context, 6f).coerceIn(12, 28), true)
        configure(1, source.x, source.y, 1, 1, 0, false)
        configure(2, source.x, source.y, 1, 1, 0, false)
        sync()
    }

    /**
     * Three rectangles are mathematically kept inside GlassBubble's ~29/64-radius circle:
     * top 12..20dp, center 20..44dp, bottom 44..52dp in its design coordinate system.
     * This approximates circular blur without ever exposing a square blurred corner outside it.
     */
    private fun configureBubble(context: Context, source: WindowManager.LayoutParams) {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        fun sx(v: Float) = source.x + (w * (v / 64f)).roundToInt()
        fun sy(v: Float) = source.y + (h * (v / 64f)).roundToInt()
        fun sw(v: Float) = (w * (v / 64f)).roundToInt().coerceAtLeast(1)
        fun sh(v: Float) = (h * (v / 64f)).roundToInt().coerceAtLeast(1)
        val radius = Ui.dp(context, 5f).coerceIn(10, 24)
        configure(0, sx(12f), sy(12f), sw(40f), sh(8f), radius, true)
        configure(1, sx(6f), sy(20f), sw(52f), sh(24f), radius, true)
        configure(2, sx(12f), sy(44f), sw(40f), sh(8f), radius, true)
        sync()
    }

    private fun configure(index: Int, x: Int, y: Int, width: Int, height: Int, radius: Int, blur: Boolean) {
        val lp = backdropParams[index]
        lp.x = x; lp.y = y; lp.width = width; lp.height = height
        lp.flags = if (blur) baseFlags() or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else baseFlags()
        lp.setBlurBehindRadius(if (blur) radius else 0)
    }

    /** Geometry is updated synchronously before FloatingWindow updates the real content window. */
    private fun sync() {
        val manager = backdropManager ?: return
        for (i in backdrops.indices) {
            try { manager.updateViewLayout(backdrops[i], backdropParams[i]) }
            catch (_: RuntimeException) { release(); return }
        }
    }

    private fun ensureBackdrops(context: Context, manager: WindowManager, currentOwner: View) {
        if (owner === currentOwner && backdrops.size == POOL && backdropManager === manager) return
        release()
        try {
            repeat(POOL) { index ->
                val view = View(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    baseFlags(), PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = 0
                    title = "Bubble localized glass backdrop ${index + 1}"
                    setBlurBehindRadius(0)
                }
                // All backdrops are inserted before FloatingWindow adds its real root, preserving
                // compositor order: other app -> blur samples -> Bubble Gecko/native UI.
                manager.addView(view, lp)
                backdrops += view; backdropParams += lp
            }
            owner = currentOwner; backdropManager = manager
            currentOwner.addOnAttachStateChangeListener(detach)
        } catch (_: RuntimeException) {
            release()
        }
    }

    private fun release() {
        val oldOwner = owner
        val manager = backdropManager
        val views = backdrops.toList()
        owner = null; backdropManager = null; backdrops.clear(); backdropParams.clear()
        oldOwner?.removeOnAttachStateChangeListener(detach)
        if (manager != null) views.forEach { view -> runCatching { manager.removeView(view) } }
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}
