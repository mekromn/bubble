package com.mekromn.bubble

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout

internal interface FloatingPageHost {
    val view: LiveGeckoView
    val transport: RendererArena.Transport
    val pageView: View
    fun show(parent: FrameLayout): Boolean
    fun geometryChanged()
    fun coverForReveal(covered: Boolean)
    fun backgroundCutout(): View?
    /** One-shot compositor readback for a transition snapshot; never used while browsing normally. */
    fun capturePagePixels(done: (Bitmap?) -> Unit)
    fun hide()
    fun destroy()
}
