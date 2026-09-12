package com.mekromn.bubble

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View

/** Draw the original glass border/chrome, but NOT over the native child page.
 * clipOutRect excludes background drawing only: it adds no bitmap, saveLayer,
 * GPU page blit or offscreen View layer. DrawableWrapper preserves the outline.
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
        // On older Android the native host cannot be active; preserve normal chrome.
        if (android.os.Build.VERSION.SDK_INT < 29 || child == null ||
            !child.isAttachedToWindow || child.width <= 0 || child.height <= 0) {
            super.draw(canvas); return
        }
        transform.reset()
        child.transformMatrixToGlobal(transform)
        owner.transformMatrixToLocal(transform)
        cutout.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
        transform.mapRect(cutout)
        val checkpoint = canvas.save()
        try { canvas.clipOutRect(cutout); super.draw(canvas) }
        finally { canvas.restoreToCount(checkpoint) }
    }
}
