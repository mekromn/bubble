package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoView

/**
 * Thin GeckoView used by both fullscreen and floating hosts. Backend/window ownership belongs to the
 * host; this class only reconciles Bubble's resident-session policy after Android view lifecycle
 * changes. Keeping this wrapper deliberately boring avoids doing compositor/window surgery from
 * inside GeckoView itself.
 */
internal class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    private fun reconcileLater() {
        main.removeCallbacks(reconcile)
        main.post(reconcile)
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
