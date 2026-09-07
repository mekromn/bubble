package com.mekromn.bubble

import android.content.Context

internal data class FloatingPanelState(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Floating chat and the conversation chooser intentionally keep independent geometry.
 * Chat still mirrors its state into Workspace for fullscreen handoff compatibility; chooser
 * geometry lives only here so resizing the chooser can never resize the webpage window.
 */
internal object FloatingPanelGeometry {
    private const val PREFS = "bubble-floating-panel-geometry-v2"

    fun load(context: Context, mode: FloatingMode, fallback: FloatingPanelState): FloatingPanelState {
        if (mode == FloatingMode.BUBBLE) return fallback
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(mode)
        if (!prefs.contains("${prefix}_w")) return fallback
        return FloatingPanelState(
            sane(prefs.getFloat("${prefix}_x", fallback.x), fallback.x),
            sane(prefs.getFloat("${prefix}_y", fallback.y), fallback.y),
            sane(prefs.getFloat("${prefix}_w", fallback.width), fallback.width),
            sane(prefs.getFloat("${prefix}_h", fallback.height), fallback.height)
        )
    }

    fun save(context: Context, mode: FloatingMode, state: FloatingPanelState) {
        if (mode == FloatingMode.BUBBLE) return
        val prefix = prefix(mode)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("${prefix}_x", state.x.coerceIn(0f, 1f))
            .putFloat("${prefix}_y", state.y.coerceIn(0f, 1f))
            .putFloat("${prefix}_w", state.width.coerceIn(.2f, 1f))
            .putFloat("${prefix}_h", state.height.coerceIn(.2f, 1f))
            .apply()
    }

    private fun prefix(mode: FloatingMode) = when (mode) {
        FloatingMode.CHAT -> "chat"
        FloatingMode.CHOOSER -> "chooser"
        FloatingMode.BUBBLE -> "bubble"
    }

    private fun sane(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
}
