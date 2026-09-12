package com.mekromn.bubble

import android.graphics.Matrix
import android.view.View

/** Maps the actual View slot to its root Surface, including surface insets.
 * Only Bubble's axis-aligned scale/translation UI animations are used here.
 * This is layout metadata; pixel buffers remain at the exact layout dimensions.
 */
internal class EmbeddedSurfacePlacement {
    data class Value(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float,
                     val alpha: Float, val visible: Boolean)
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val surface = IntArray(2)
    private val screen = IntArray(2)

    // This reader is called only by the API-36 native floating host.
    @androidx.annotation.RequiresApi(29)
    fun read(view: View, covered: Boolean): Value {
        matrix.reset(); view.transformMatrixToGlobal(matrix); matrix.getValues(values)
        view.getLocationInSurface(surface); view.getLocationOnScreen(screen)
        var opacity = 1f
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) opacity = 0f
            opacity *= current.alpha
            current = current.parent as? View
        }
        // ViewRoot/window rotation is inherited through the SurfaceControl parent;
        // no arbitrary in-hierarchy rotation or perspective is introduced by Bubble.
        val sx = values[Matrix.MSCALE_X]
        val sy = values[Matrix.MSCALE_Y]
        val visible = !covered && view.isShown && view.windowVisibility == View.VISIBLE && opacity > 0f
        return Value(values[Matrix.MTRANS_X] + surface[0] - screen[0],
            values[Matrix.MTRANS_Y] + surface[1] - screen[1], sx, sy,
            opacity.coerceIn(0f, 1f), visible)
    }
}
