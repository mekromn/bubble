package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import org.mozilla.geckoview.GeckoView

/**
 * Gecko's View may change activity state after the app lifecycle callback. Reconcile once after
 * that event, never via a polling loop. Reassert activity, not OS foreground privileges.
 *
 * Fullscreen uses GeckoView's normal SurfaceView backend in the activity window. FloatingWindow still
 * issues its historical TextureView request as the marker that this GeckoView belongs to the floating
 * page slot, but TextureView is never instantiated. Instead we create Gecko's SurfaceView backend,
 * remember the attached page-slot ViewGroup as a geometry anchor, then move this same GeckoView into a
 * dedicated opaque TYPE_APPLICATION_OVERLAY window. The floating native header/footer remain in their
 * translucent glass window while Gecko owns a normal hardware-composed SurfaceView window covering
 * only the page rectangle.
 *
 * The anchor stays in the chrome hierarchy and reports its real screen bounds through normal pre-draw
 * traversal. We update the Gecko window only when those bounds actually change, so drag/resize follows
 * the native frame without a timer or allocation loop. When the page slot is removed (chooser, bubble,
 * destroy), the dedicated Gecko window is removed immediately and the same GeckoView can be attached
 * again the next time CHAT mode opens.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }
    private val location = IntArray(2)

    private var floatingDirectSurface = false
    private var promoteQueued = false
    private var dedicated = false
    private var stoppingDedicated = false
    private var anchor: ViewGroup? = null
    private var dedicatedManager: WindowManager? = null
    private var dedicatedParams: WindowManager.LayoutParams? = null
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastWidth = -1
    private var lastHeight = -1

    private val anchorAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            if (v === anchor) stopDedicatedWindow()
        }
    }

    private val anchorPreDraw = ViewTreeObserver.OnPreDrawListener {
        syncDedicatedGeometry()
        true
    }

    override fun setViewBackend(backend: Int) {
        if (backend == BACKEND_TEXTURE_VIEW) {
            check(!isAttachedToWindow) { "Floating SurfaceView must be selected before attachment" }
            floatingDirectSurface = true
            super.setViewBackend(BACKEND_SURFACE_VIEW)
        } else {
            super.setViewBackend(backend)
        }
    }

    private fun queuePromotion() {
        if (!floatingDirectSurface || dedicated || promoteQueued || stoppingDedicated) return
        promoteQueued = true
        main.post {
            promoteQueued = false
            promoteToDedicatedWindow()
        }
    }

    private fun promoteToDedicatedWindow() {
        if (!floatingDirectSurface || dedicated || stoppingDedicated) return
        val host = (parent as? ViewGroup) ?: anchor ?: return
        if (!host.isAttachedToWindow) return
        if (host.width <= 0 || host.height <= 0) {
            host.postOnAnimation { promoteToDedicatedWindow() }
            return
        }

        anchor = host
        host.addOnAttachStateChangeListener(anchorAttach)
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.addOnPreDrawListener(anchorPreDraw)
        host.getLocationOnScreen(location)

        val params = WindowManager.LayoutParams(
            host.width.coerceAtLeast(1),
            host.height.coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = location[0]
            y = location[1]
            title = "Bubble direct Gecko surface"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val manager = context.getSystemService(WindowManager::class.java)
        dedicatedManager = manager
        dedicatedParams = params
        lastX = params.x
        lastY = params.y
        lastWidth = params.width
        lastHeight = params.height

        // The page-slot parent remains as the geometry anchor. Only Gecko moves into its own window.
        host.removeView(this)
        setBackgroundColor(Color.BLACK)
        dedicated = true
        try {
            RenderPolicy.vote(context, this, params)
            manager.addView(this, params)
        } catch (_: RuntimeException) {
            dedicated = false
            dedicatedManager = null
            dedicatedParams = null
            unregisterAnchor()
            // Keep the SurfaceView object alive in the original slot rather than silently falling back
            // to TextureView. This is deliberately fail-visible so the fast path can be debugged.
            if (parent == null && host.isAttachedToWindow) {
                runCatching { host.addView(this, ViewGroup.LayoutParams(-1, -1)) }
            }
        }
    }

    private fun syncDedicatedGeometry() {
        if (!dedicated || stoppingDedicated) return
        val host = anchor ?: return
        if (!host.isAttachedToWindow) {
            stopDedicatedWindow()
            return
        }
        val params = dedicatedParams ?: return
        val manager = dedicatedManager ?: return
        val width = host.width.coerceAtLeast(1)
        val height = host.height.coerceAtLeast(1)
        host.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        if (x == lastX && y == lastY && width == lastWidth && height == lastHeight) return

        params.x = x
        params.y = y
        params.width = width
        params.height = height
        try {
            manager.updateViewLayout(this, params)
            lastX = x
            lastY = y
            lastWidth = width
            lastHeight = height
        } catch (_: RuntimeException) {
            stopDedicatedWindow()
        }
    }

    private fun unregisterAnchor() {
        val host = anchor
        anchor = null
        host?.removeOnAttachStateChangeListener(anchorAttach)
        val observer = host?.viewTreeObserver
        if (observer?.isAlive == true) runCatching { observer.removeOnPreDrawListener(anchorPreDraw) }
    }

    private fun stopDedicatedWindow() {
        if (stoppingDedicated) return
        stoppingDedicated = true
        promoteQueued = false
        main.removeCallbacksAndMessages(null)
        unregisterAnchor()
        val manager = dedicatedManager
        dedicatedManager = null
        dedicatedParams = null
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastWidth = -1
        lastHeight = -1
        if (dedicated) {
            dedicated = false
            if (manager != null && isAttachedToWindow) runCatching { manager.removeViewImmediate(this) }
        }
        stoppingDedicated = false
        reconcileLater()
    }

    private fun reconcileLater() {
        main.removeCallbacks(reconcile)
        main.post(reconcile)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (floatingDirectSurface && !dedicated && parent is ViewGroup) queuePromotion()
        reconcileLater()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reconcileLater()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        reconcileLater()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reconcileLater()
    }
}
