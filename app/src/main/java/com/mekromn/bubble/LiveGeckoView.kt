package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoView

/**
 * Gecko's View may change activity state after the app lifecycle callback. Reconcile once after
 * that event, never via a polling loop. Reassert activity, not OS foreground privileges.
 *
 * Backend choice is intentionally left to the host. Fullscreen BrowserActivity uses GeckoView's
 * default SurfaceView fast path. FloatingWindow explicitly requests TextureView because an
 * interactive TYPE_APPLICATION_OVERLAY must composite, clip and move the page as part of the
 * normal Android View hierarchy. Do not globally coerce floating TextureView back to SurfaceView:
 * on the target Pixel that produced a live GeckoSession with a transparent/non-rendering page.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    private fun reconcileLater() { main.removeCallbacks(reconcile); main.post(reconcile) }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); reconcileLater()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus); reconcileLater()
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); reconcileLater() }
}
