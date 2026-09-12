package com.mekromn.bubble

import android.view.View

/** SurfaceView owns surface-space placement. Only opacity/reveal coverage
 * needs app metadata; applying absolute geometry again would double it. */
internal class EmbeddedSurfacePlacement {
    data class Value(val alpha: Float, val visible: Boolean)
    fun read(view: View, covered: Boolean): Value {
        var opacity = 1f
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) opacity = 0f
            opacity *= current.alpha
            current = current.parent as? View
        }
        val visible = !covered && view.isShown && view.windowVisibility == View.VISIBLE && opacity > 0f
        return Value(opacity.coerceIn(0f, 1f), visible)
    }
}
