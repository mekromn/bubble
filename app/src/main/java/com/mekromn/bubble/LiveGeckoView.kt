package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Thin GeckoView used by fullscreen and as the bookkeeping bridge for the raw floating display.
 * Backend/window ownership belongs to the host; this class only reconciles Bubble's resident-session
 * policy after Android view lifecycle changes. Keeping this wrapper deliberately boring avoids doing
 * compositor/window surgery from inside GeckoView itself.
 */
internal open class LiveGeckoView(context: Context) : GeckoView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { Workspace.peek()?.applyPolicy() }

    private fun reconcileLater() {
        main.removeCallbacks(reconcile)
        main.post(reconcile)
    }

    override fun setSession(session: GeckoSession) {
        DiagnosticLog.event(
            "GECKOVIEW",
            "setSession begin view=${Integer.toHexString(System.identityHashCode(this))} attached=$isAttachedToWindow " +
                "old=${DiagnosticLog.sessionLabel(this.session)} new=${DiagnosticLog.sessionLabel(session)}"
        )
        super.setSession(session)
        DiagnosticLog.event(
            "GECKOVIEW",
            "setSession complete view=${Integer.toHexString(System.identityHashCode(this))} attached=$isAttachedToWindow current=${DiagnosticLog.sessionLabel(this.session)}"
        )
    }

    override fun releaseSession(): GeckoSession? {
        val before = session
        DiagnosticLog.event(
            "GECKOVIEW",
            "releaseSession begin view=${Integer.toHexString(System.identityHashCode(this))} attached=$isAttachedToWindow current=${DiagnosticLog.sessionLabel(before)}"
        )
        val released = super.releaseSession()
        DiagnosticLog.event(
            "GECKOVIEW",
            "releaseSession complete view=${Integer.toHexString(System.identityHashCode(this))} released=${DiagnosticLog.sessionLabel(released)}"
        )
        return released
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        DiagnosticLog.event(
            "GECKOVIEW",
            "windowVisibility=$visibility view=${Integer.toHexString(System.identityHashCode(this))} attached=$isAttachedToWindow ${DiagnosticLog.sessionLabel(session)}"
        )
        super.onWindowVisibilityChanged(visibility)
        reconcileLater()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        DiagnosticLog.event(
            "GECKOVIEW",
            "windowFocus=$hasWindowFocus view=${Integer.toHexString(System.identityHashCode(this))} attached=$isAttachedToWindow ${DiagnosticLog.sessionLabel(session)}"
        )
        super.onWindowFocusChanged(hasWindowFocus)
        reconcileLater()
    }

    override fun onDetachedFromWindow() {
        DiagnosticLog.event(
            "GECKOVIEW",
            "onDetachedFromWindow begin view=${Integer.toHexString(System.identityHashCode(this))} ${DiagnosticLog.sessionLabel(session)}"
        )
        super.onDetachedFromWindow()
        DiagnosticLog.event("GECKOVIEW", "onDetachedFromWindow complete view=${Integer.toHexString(System.identityHashCode(this))}")
        reconcileLater()
    }
}
