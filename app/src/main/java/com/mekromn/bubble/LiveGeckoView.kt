package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoView

/** Gecko's View may change activity state after the app's lifecycle callback. Reconcile once
 * after that event, never via a polling loop. Reassert activity, not OS foreground privileges. */
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
