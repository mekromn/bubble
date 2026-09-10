package com.mekromn.bubble

/**
 * Immediate fullscreen Gecko surface reconciliation for explicit UI tab changes.
 *
 * Workspace.changed() intentionally coalesces general UI updates through Choreographer, but an
 * explicit fullscreen tab switch must not depend on that deferred callback: the selected tab can
 * change while the fullscreen chooser is covering the Activity and leave GeckoView displaying the
 * previous session until another window transition forces a render.
 *
 * This performs no navigation/reload and creates no session. It only attaches the already-selected,
 * already-open resident GeckoSession to the existing fullscreen GeckoView.
 */
internal fun BrowserActivity.syncSelectedSurfaceNow() {
    val tab = workspace.selected ?: return
    val session = tab.session
    if (session != null && session.isOpen) {
        workspace.attachSurface(geckoView, session)
    } else if (geckoView.session != null) {
        workspace.detachSurface(geckoView)
    }
    workspace.applyPolicy()
    geckoView.postInvalidateOnAnimation()
}
