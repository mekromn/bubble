package com.mekromn.bubble

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View

/**
 * Draw Bubble's card material around, but never over, the native Gecko page.
 *
 * The page now lives in the same ViewRoot as the chrome, so the old cross-window two-pixel seam
 * underpaint is unnecessary. The exact child rectangle is clipped out of the background draw. This
 * adds no bitmap, saveLayer, page texture, offscreen View layer or per-page-frame work.
 */
internal class EmbeddedPageBackground(
    drawable: Drawable,
    private val owner: View,
    private val page: () -> View?
) : DrawableWrapper(drawable) {
    private val transform = Matrix()
    private val cutout = RectF()

    override fun draw(canvas: Canvas) {
        val child = page()
        if (android.os.Build.VERSION.SDK_INT < 29 || child == null ||
            !child.isAttachedToWindow || child.width <= 0 || child.height <= 0) {
            super.draw(canvas)
            return
        }
        transform.reset()
        child.transformMatrixToGlobal(transform)
        owner.transformMatrixToLocal(transform)
        cutout.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
        transform.mapRect(cutout)
        val checkpoint = canvas.save()
        try {
            canvas.clipOutRect(cutout)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}
