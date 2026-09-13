package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView

/**
 * Thin GeckoView used by fullscreen and as the bookkeeping bridge for the raw floating display.
 * Backend/window ownership belongs to the host; this class only reconciles Bubble's resident-session
 * policy after Android view lifecycle changes. Keeping this wrapper deliberately boring avoids doing
 * compositor/window surgery or diagnostic string construction from inside GeckoView itself.
 *
 * The floating RawSessionBridge intentionally never attaches to Android's View hierarchy. Workspace
 * still uses its GeckoView-shaped owner to decide GeckoSession focused state, so an unqualified
 * View.hasWindowFocus() would permanently report false for the floating selected tab. Attached
 * fullscreen GeckoViews continue using Android's real focus; only the active detached floating bridge
 * reports focus while its interactive floating page is visible.
 */
internal open class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    private fun reconcileLater() {
        main.removeCallbacks(reconcile)
        main.post(reconcile)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        PageTouchDispatch.request(this, event, session != null)
        return super.onTouchEvent(event)
    }

    override fun hasWindowFocus(): Boolean {
        if (isAttachedToWindow) return super.hasWindowFocus()
        val floating = BubbleService.active?.window
        val workspace = Workspace.peek()
        return floating?.geckoView === this && workspace?.floatingVisible == true
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
