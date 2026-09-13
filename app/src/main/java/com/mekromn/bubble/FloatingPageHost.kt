package com.mekromn.bubble

import android.view.View
import android.widget.FrameLayout

internal interface FloatingPageHost {
    val view: LiveGeckoView
    val transport: RendererArena.Transport
    fun show(parent: FrameLayout): Boolean
    fun geometryChanged()
    fun coverForReveal(covered: Boolean)
    fun backgroundCutout(): View?
    fun hide()
    fun destroy()
}
