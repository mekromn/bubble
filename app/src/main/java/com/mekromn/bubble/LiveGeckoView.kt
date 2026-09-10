package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoView

/**
 * Gecko's View may change activity state after the app lifecycle callback. Reconcile once after
 * that event, never via a polling loop. Reassert activity, not OS foreground privileges.
 *
 * Bubble is a browser first: every interactive Gecko surface uses SurfaceView. GeckoView documents
 * SurfaceView as its best-performance backend; TextureView exists for transforming/stacking the
 * webpage itself and adds avoidable composition cost. Bubble animates native chrome/transition
 * captures instead, so a caller asking for TextureView is intentionally coerced back to SurfaceView.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    override fun setViewBackend(backend: Int) {
        super.setViewBackend(BACKEND_SURFACE_VIEW)
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
