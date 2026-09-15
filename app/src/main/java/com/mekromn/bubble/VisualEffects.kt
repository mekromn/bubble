package com.mekromn.bubble

import android.content.Context

/**
 * One process-wide switch for expensive translucent compositing.
 *
 * When transparency is disabled Bubble keeps the same geometry, typography and control layout but
 * renders its persistent chrome fully opaque and disables compositor background blur. This removes
 * the largest avoidable blend/blur cost without lowering Gecko/page quality, resolution or refresh
 * policy. The preference is intentionally synchronous and tiny so it is available before the first
 * Activity/window is built.
 */
internal object VisualEffects {
    private const val PREFS = "bubble-visual-effects-v1"
    private const val KEY_TRANSPARENCY = "transparency"

    @Volatile private var initialized = false
    @Volatile private var transparency = true

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            transparency = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRANSPARENCY, true)
            initialized = true
        }
    }

    fun transparencyEnabled(): Boolean = transparency

    fun setTransparency(context: Context, enabled: Boolean) {
        initialize(context)
        transparency = enabled
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRANSPARENCY, enabled).apply()
    }
}
