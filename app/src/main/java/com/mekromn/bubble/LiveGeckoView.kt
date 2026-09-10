package com.mekromn.bubble

import android.content.Context
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
 * Fullscreen keeps GeckoView's normal SurfaceView backend. FloatingWindow historically asks for a
 * TextureView; on the target Pixel that renders reliably inside TYPE_APPLICATION_OVERLAY but pays
 * Gecko/Android's slower texture-composition path. For the direct-surface experiment, intercept that
 * one floating request while the view is still detached, create Gecko's SurfaceView backend, and
 * raise the actual SurfaceView above the translucent overlay window before WindowManager attachment.
 * The page occupies only the rectangle between Bubble's native header/footer, so top-Z page pixels
 * do not need to overlap the glass chrome.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    override fun setViewBackend(backend: Int) {
        if (backend != BACKEND_TEXTURE_VIEW) {
            super.setViewBackend(backend)
            return
        }
        check(!isAttachedToWindow) { "Floating SurfaceView must be configured before overlay attachment" }
        super.setViewBackend(BACKEND_SURFACE_VIEW)
        val surface = findSurfaceView(this)
            ?: error("Gecko SurfaceView backend did not expose a SurfaceView")
        surface.setZOrderOnTop(true)
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

    private fun reconcileLater() { main.removeCallbacks(reconcile); main.post(reconcile) }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); reconcileLater()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus); reconcileLater()
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); reconcileLater() }
}
