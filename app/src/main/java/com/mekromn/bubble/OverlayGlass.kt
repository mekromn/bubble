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
 * Two non-interactive backdrop windows are created before FloatingWindow adds its real overlay.
 * Keeping both alive (with the unused one collapsed to 1x1) preserves their Z-order underneath the
 * interactive window across CHAT/BUBBLE/CHOOSER transitions; we never create a new blur window above
 * an already-attached Gecko surface. No screenshots, bitmap caches, polling or RenderEffect tricks.
 */
internal object OverlayGlass {
    private var owner: View? = null
    private var primary: Dialog? = null
    private var secondary: Dialog? = null
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
        window.setBackgroundDrawable(glassShape(floatArrayOf(1f,1f,1f,1f,1f,1f,1f,1f)))
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
        val first = primary ?: return
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val corner = if (expanded) Ui.dp(context, 26f).toFloat() else min(width, height) / 2f
        val blurRadius = if (expanded) Ui.dp(context, 18f).coerceIn(36, 72)
            else Ui.dp(context, 14f).coerceIn(28, 60)
        update(first, source.x, source.y, width, height,
            floatArrayOf(corner,corner, corner,corner, corner,corner, corner,corner), blurRadius)
        disable(secondary, source.x, source.y)
    }

    @SuppressLint("NewApi")
    private fun updateChatChrome(context: Context, source: WindowManager.LayoutParams) {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val topHeight = Ui.dp(context, 52f).coerceAtMost(height)
        val bottomHeight = Ui.dp(context, 48f).coerceAtMost((height - topHeight).coerceAtLeast(0))
        val corner = Ui.dp(context, 26f).toFloat()
        val blurRadius = Ui.dp(context, 18f).coerceIn(36, 72)

        // Only these two native chrome strips have blur. The page region in between has no blur
        // window underneath it at all.
        update(primary ?: return, source.x, source.y, width, topHeight.coerceAtLeast(1),
            floatArrayOf(corner,corner, corner,corner, 0f,0f, 0f,0f), blurRadius)
        if (bottomHeight > 0) {
            update(secondary ?: return, source.x, source.y + height - bottomHeight, width, bottomHeight,
                floatArrayOf(0f,0f, 0f,0f, corner,corner, corner,corner), blurRadius)
        } else disable(secondary, source.x, source.y + height)
    }

    @SuppressLint("NewApi")
    private fun update(dialog: Dialog, x: Int, y: Int, width: Int, height: Int,
        corners: FloatArray, blurRadius: Int) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(glassShape(corners))
        window.setBackgroundBlurRadius(blurRadius)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y
            this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
    }

    @SuppressLint("NewApi")
    private fun disable(dialog: Dialog?, x: Int, y: Int) {
        val window = dialog?.window ?: return
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(ColorDrawableCompat.transparent())
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y; width = 1; height = 1
            format = PixelFormat.TRANSLUCENT; dimAmount = 0f
            flags = (flags or baseFlags()) and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
    }

    /** Tiny non-zero fill gives Android a concrete outline from which to clip background blur. */
    private fun glassShape(cornerRadii: FloatArray) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x01000000)
        this.cornerRadii = cornerRadii
    }

    /** Avoid an android.graphics.drawable.ColorDrawable import solely for a 1x1 disabled window. */
    private object ColorDrawableCompat {
        fun transparent() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    }

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
