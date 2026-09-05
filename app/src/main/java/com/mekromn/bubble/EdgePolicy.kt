package com.mekromn.bubble

import kotlin.math.abs

/** Android-free gesture decisions. No fake touch replay into the application below the strip. */
internal data class EdgeOptions(val enabled: Boolean = false, val left: Boolean = false,
    val position: Float = .5f, val heightDp: Int = 104, val widthDp: Int = 18,
    val indicator: Boolean = true) {
    fun sanitized() = copy(position = if (position.isFinite()) position.coerceIn(0f, 1f) else .5f,
        heightDp = heightDp.coerceIn(64, 160), widthDp = widthDp.coerceIn(12, 28))
}
internal object EdgePolicy {
    fun inward(dx: Float, left: Boolean) = if (left) dx else -dx
    fun opens(dx: Float, dy: Float, elapsedMs: Long, left: Boolean, distance: Float): Boolean =
        dx.isFinite() && dy.isFinite() && elapsedMs in 0..1600 &&
            inward(dx, left) >= distance && inward(dx, left) > abs(dy) * 1.5f
    fun vertical(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) > abs(dx) * 1.5f
}
