package com.mekromn.bubble.display

import kotlin.math.abs

enum class RefreshRateMode {
    AUTO,
    HZ_60,
    HZ_90,
    HZ_120_PLUS,
    HIGHEST,
}

object RefreshRatePolicy {
    fun resolveSupportedRate(mode: RefreshRateMode, supportedRates: Iterable<Float>): Float {
        if (mode == RefreshRateMode.AUTO) return 0f
        val rates = supportedRates
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
        if (rates.isEmpty()) return when (mode) {
            RefreshRateMode.AUTO -> 0f
            RefreshRateMode.HZ_60 -> 60f
            RefreshRateMode.HZ_90 -> 90f
            RefreshRateMode.HZ_120_PLUS,
            RefreshRateMode.HIGHEST,
            -> 120f
        }

        return when (mode) {
            RefreshRateMode.AUTO -> 0f
            RefreshRateMode.HZ_60 -> closest(rates, 60f)
            RefreshRateMode.HZ_90 -> closest(rates, 90f)
            RefreshRateMode.HZ_120_PLUS -> rates.filter { it >= HIGH_REFRESH_THRESHOLD_HZ }
                .maxOrNull()
                ?: rates.max()
            RefreshRateMode.HIGHEST -> rates.max()
        }
    }

    private fun closest(rates: List<Float>, target: Float): Float = rates.minWith(
        compareBy<Float>({ abs(it - target) }, { -it }),
    )

    private const val HIGH_REFRESH_THRESHOLD_HZ = 119f
}
