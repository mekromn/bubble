package com.mekromn.bubble

/** Pixel rectangles for the floating surface. Pure math so every clamp has unit coverage. */
internal data class WindowBox(val x: Int, val y: Int, val width: Int, val height: Int)
internal object WindowGeometry {
    fun fit(box: WindowBox, safe: WindowBox): WindowBox {
        val width = box.width.coerceIn(1, safe.width.coerceAtLeast(1))
        val height = box.height.coerceIn(1, safe.height.coerceAtLeast(1))
        return WindowBox(box.x.coerceIn(safe.x, safe.x + safe.width.coerceAtLeast(1) - width),
            box.y.coerceIn(safe.y, safe.y + safe.height.coerceAtLeast(1) - height), width, height)
    }
    fun fraction(value: Float, fallback: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    fun placed(safe: WindowBox, x: Float, y: Float, width: Int, height: Int): WindowBox {
        val w = width.coerceIn(1, safe.width.coerceAtLeast(1))
        val h = height.coerceIn(1, safe.height.coerceAtLeast(1))
        return WindowBox(safe.x + ((safe.width - w) * fraction(x, .5f)).toInt(),
            safe.y + ((safe.height - h) * fraction(y, .3f)).toInt(), w, h)
    }
}
