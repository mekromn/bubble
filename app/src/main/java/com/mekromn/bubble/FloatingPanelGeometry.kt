package com.mekromn.bubble

import android.content.Context
import android.content.SharedPreferences

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
 *
 * Geometry is read repeatedly while opening/resizing panels. SharedPreferences itself caches disk
 * data, but repeatedly resolving the preferences object and decoding four keys still creates work.
 * This object is the sole writer for these keys, so keep an in-process sanitized state cache and
 * write through asynchronously. Configuration changes do not invalidate normalized geometry.
 */
internal object FloatingPanelGeometry {
    private const val PREFS = "bubble-floating-panel-geometry-v2"
    private var preferences: SharedPreferences? = null
    private val cache = HashMap<FloatingMode, FloatingPanelState>()

    fun load(context: Context, mode: FloatingMode, fallback: FloatingPanelState): FloatingPanelState {
        if (mode == FloatingMode.BUBBLE) return fallback
        synchronized(cache) { cache[mode]?.let { return it } }
        val prefs = prefs(context)
        val prefix = prefix(mode)
        val state = if (!prefs.contains("${prefix}_w")) fallback else FloatingPanelState(
            sane(prefs.getFloat("${prefix}_x", fallback.x), fallback.x),
            sane(prefs.getFloat("${prefix}_y", fallback.y), fallback.y),
            sane(prefs.getFloat("${prefix}_w", fallback.width), fallback.width),
            sane(prefs.getFloat("${prefix}_h", fallback.height), fallback.height)
        )
        val clean = sanitize(state)
        synchronized(cache) { cache[mode] = clean }
        return clean
    }

    fun save(context: Context, mode: FloatingMode, state: FloatingPanelState) {
        if (mode == FloatingMode.BUBBLE) return
        val clean = sanitize(state)
        synchronized(cache) { cache[mode] = clean }
        val prefix = prefix(mode)
        prefs(context).edit()
            .putFloat("${prefix}_x", clean.x)
            .putFloat("${prefix}_y", clean.y)
            .putFloat("${prefix}_w", clean.width)
            .putFloat("${prefix}_h", clean.height)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        synchronized(cache) {
            preferences?.let { return it }
            return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { preferences = it }
        }
    }

    private fun sanitize(state: FloatingPanelState) = FloatingPanelState(
        sane(state.x, .5f).coerceIn(0f, 1f),
        sane(state.y, .25f).coerceIn(0f, 1f),
        sane(state.width, .92f).coerceIn(.2f, 1f),
        sane(state.height, .72f).coerceIn(.2f, 1f)
    )

    private fun prefix(mode: FloatingMode) = when (mode) {
        FloatingMode.CHAT -> "chat"
        FloatingMode.CHOOSER -> "chooser"
        FloatingMode.BUBBLE -> "bubble"
    }

    private fun sane(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
}
