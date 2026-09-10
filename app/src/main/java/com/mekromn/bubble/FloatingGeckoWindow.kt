package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import org.mozilla.geckoview.GeckoView

/**
 * First-class floating page window.
 *
 * Gecko never enters Bubble's translucent chrome hierarchy. The GeckoView is born as a normal
 * SurfaceView and is attached directly as the root of its own opaque, hardware-accelerated
 * TYPE_APPLICATION_OVERLAY window. FloatingWindow owns this object and feeds it the exact page
 * rectangle between the 52dp header and 48dp utility strip.
 *
 * This is intentionally simpler than the failed same-window and reparenting experiments: there is
 * no TextureView, no setZOrderOnTop/setCompositionOrder, no attach-detach promotion cycle, and no
 * polling loop. The only hot-path operation during move/resize is one updateViewLayout when the
 * page rectangle actually changes.
 */
internal class FloatingGeckoWindow(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)

    val view = LiveGeckoView(context).apply {
        setViewBackend(GeckoView.BACKEND_SURFACE_VIEW)
        setBackgroundColor(Color.BLACK)
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        if (attached) {
            sync(safe)
            return true
        }

        val layout = WindowManager.LayoutParams(
            safe.width,
            safe.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = safe.x
            y = safe.y
            title = "Bubble Gecko page"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return try {
            RenderPolicy.vote(context, view, layout)
            manager.addView(view, layout)
            params = layout
            lastBox = safe
            attached = true
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach floating Gecko SurfaceView window", error)
            Toast.makeText(context, "Floating Gecko window failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            params = null
            lastBox = null
            attached = false
            false
        }
    }

    fun sync(box: WindowBox) {
        if (!attached) return
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        if (lastBox == safe) return
        val layout = params ?: return
        layout.x = safe.x
        layout.y = safe.y
        layout.width = safe.width
        layout.height = safe.height
        try {
            manager.updateViewLayout(view, layout)
            lastBox = safe
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not move floating Gecko SurfaceView window", error)
            hide()
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        params = null
        lastBox = null
        runCatching { manager.removeViewImmediate(view) }
    }

    fun destroy() = hide()

    companion object {
        private const val TAG = "BubbleFloatingGecko"
    }
}
