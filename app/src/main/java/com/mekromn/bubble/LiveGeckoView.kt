package com.mekromn.bubble

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import org.mozilla.geckoview.GeckoView

/**
 * Gecko's View may change activity state after the app lifecycle callback. Reconcile once after
 * that event, never via a polling loop. Reassert activity, not OS foreground privileges.
 *
 * Fullscreen keeps GeckoView's normal SurfaceView backend. FloatingWindow still issues its historical
 * TextureView request as an A/B trigger, but this class never instantiates TextureView: the request is
 * translated to SurfaceView while detached.
 *
 * On Android 16/API 36, use SurfaceView.setCompositionOrder() instead of the legacy top-Z API. A
 * non-negative composition order places the SurfaceView above its parent window. Gecko documents that
 * SurfaceView is its best-performance backend but cannot participate in normal View transforms, so a
 * floating SurfaceView also neutralizes alpha/scale/translation on its ancestor chain when attached.
 * Native chrome can animate independently; the browser surface itself stays on the direct compositor
 * path and is never transformed as a TextureView would be.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }
    private var floatingDirectSurface = false

    override fun setViewBackend(backend: Int) {
        if (backend != BACKEND_TEXTURE_VIEW) {
            super.setViewBackend(backend)
            return
        }
        check(!isAttachedToWindow) { "Floating SurfaceView must be configured before overlay attachment" }
        floatingDirectSurface = true
        super.setViewBackend(BACKEND_SURFACE_VIEW)
        val surface = findSurfaceView(this)
            ?: error("Gecko SurfaceView backend did not expose a SurfaceView")
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16's explicit SurfaceView composition-order API avoids the legacy punched-hole
            // Z-order path inside a TYPE_APPLICATION_OVERLAY.
            surface.setCompositionOrder(1)
        } else {
            surface.setZOrderOnTop(true)
        }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** SurfaceView is not a transformable Gecko backend. Keep every ancestor on an identity transform. */
    private fun neutralizeFloatingTransforms() {
        if (!floatingDirectSurface) return
        var view: View? = this
        while (view != null) {
            view.animate().cancel()
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view = view.parent as? View
        }
    }

    private fun reconcileLater() { main.removeCallbacks(reconcile); main.post(reconcile) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        neutralizeFloatingTransforms()
        reconcileLater()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) neutralizeFloatingTransforms()
        reconcileLater()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) neutralizeFloatingTransforms()
        reconcileLater()
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); reconcileLater() }
}
