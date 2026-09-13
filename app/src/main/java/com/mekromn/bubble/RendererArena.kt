package com.mekromn.bubble

/**
 * Runtime-only renderer arena. Default remains the user-confirmed relay_latest_bp path.
 * Switching transport does not change Gecko engine, profile, page, input policy, quality,
 * hardware preferences, or floating chrome.
 */
internal object RendererArena {
    enum class Transport { RELAY_LATEST_BP, DIRECT_GECKO_SURFACE }

    @Volatile var transport: Transport = Transport.RELAY_LATEST_BP

    fun createHost(context: android.content.Context): FloatingPageHost = when (transport) {
        Transport.RELAY_LATEST_BP -> FloatingGeckoWindow(context)
        Transport.DIRECT_GECKO_SURFACE -> DirectGeckoWindow(context)
    }
}
