package com.mekromn.bubble.browser.session

enum class RendererMemoryMode {
    SAVE_MEMORY,
    BALANCED,
    KEEP_MORE,
}

enum class RendererMemoryPressure {
    NORMAL,
    MODERATE,
    LOW,
    CRITICAL,
}

/**
 * Pure renderer-retention policy. Logical tab count never controls whether a tab may exist;
 * it only caps how many non-explicitly-kept renderers Bubble volunteers to keep resident.
 */
class RendererMemoryPolicy(
    private val memoryClassMb: Int,
) {
    fun warmBudget(
        mode: RendererMemoryMode,
        pressure: RendererMemoryPressure,
    ): Int {
        val normal = when (mode) {
            RendererMemoryMode.SAVE_MEMORY -> (baseWarmBudget() - 1).coerceAtLeast(0)
            RendererMemoryMode.BALANCED -> baseWarmBudget()
            RendererMemoryMode.KEEP_MORE -> baseWarmBudget() + 2
        }
        return when (pressure) {
            RendererMemoryPressure.NORMAL -> normal
            RendererMemoryPressure.MODERATE -> (normal - 1).coerceAtLeast(0)
            RendererMemoryPressure.LOW -> if (mode == RendererMemoryMode.KEEP_MORE) 1 else 0
            RendererMemoryPressure.CRITICAL -> 0
        }
    }

    fun snapshotBudgetBytes(
        mode: RendererMemoryMode,
        pressure: RendererMemoryPressure,
    ): Long {
        val base = when {
            memoryClassMb >= 512 -> 24L * MIB
            memoryClassMb >= 256 -> 16L * MIB
            else -> 8L * MIB
        }
        val modeAdjusted = when (mode) {
            RendererMemoryMode.SAVE_MEMORY -> base / 2
            RendererMemoryMode.BALANCED -> base
            RendererMemoryMode.KEEP_MORE -> (base * 3) / 2
        }
        val pressureAdjusted = when (pressure) {
            RendererMemoryPressure.NORMAL -> modeAdjusted
            RendererMemoryPressure.MODERATE -> (modeAdjusted * 3) / 4
            RendererMemoryPressure.LOW -> modeAdjusted / 2
            RendererMemoryPressure.CRITICAL -> modeAdjusted / 4
        }
        return pressureAdjusted.coerceAtLeast(MIN_TOTAL_SNAPSHOT_BYTES)
    }

    fun maxManagedLiveRenderers(
        logicalTabCount: Int,
        mode: RendererMemoryMode,
        pressure: RendererMemoryPressure,
    ): Int {
        if (logicalTabCount <= 0) return 0
        return 1 + minOf(
            logicalTabCount - 1,
            warmBudget(mode, pressure),
        )
    }

    private fun baseWarmBudget(): Int = when {
        memoryClassMb >= 512 -> 4
        memoryClassMb >= 256 -> 3
        memoryClassMb >= 192 -> 2
        else -> 1
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val MIN_TOTAL_SNAPSHOT_BYTES = 2L * MIB

        /**
         * Maps both current and historical ComponentCallbacks2 levels without making the pure
         * policy depend on Android framework classes. UI-hidden is treated as moderate pressure
         * so Bubble opportunistically sheds one warm renderer while backgrounded.
         */
        fun pressureForTrimLevel(level: Int): RendererMemoryPressure = when {
            level >= 80 -> RendererMemoryPressure.CRITICAL
            level >= 40 -> RendererMemoryPressure.LOW
            level == 20 -> RendererMemoryPressure.MODERATE
            level >= 15 -> RendererMemoryPressure.CRITICAL
            level >= 10 -> RendererMemoryPressure.LOW
            level >= 5 -> RendererMemoryPressure.MODERATE
            else -> RendererMemoryPressure.NORMAL
        }
    }
}
