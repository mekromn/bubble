package com.mekromn.bubble

/**
 * Renderer selection shared by the hybrid production candidate and its safety fallback.
 * Direct GeckoDisplay -> SurfaceView is the steady-state default. relay_latest_bp remains
 * available only as a fallback if Android/Gecko cannot establish the direct surface.
 */
internal object RendererArena {
    enum class Transport { RELAY_LATEST_BP, DIRECT_GECKO_SURFACE }

    @Volatile var transport: Transport = Transport.DIRECT_GECKO_SURFACE

    fun createHost(context: android.content.Context): FloatingPageHost = when (transport) {
        Transport.RELAY_LATEST_BP -> FloatingGeckoWindow(context)
        Transport.DIRECT_GECKO_SURFACE -> DirectGeckoWindow(context)
    }

    fun createFallback(context: android.content.Context): FloatingPageHost = FloatingGeckoWindow(context)
}
