package com.mekromn.bubble.browser.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererMemoryPolicyTest {
    private val policy = RendererMemoryPolicy(memoryClassMb = 512)

    @Test
    fun hundredLogicalTabsDoNotCreateProportionalManagedRenderers() {
        val liveForHundred = policy.maxManagedLiveRenderers(
            logicalTabCount = 100,
            mode = RendererMemoryMode.BALANCED,
            pressure = RendererMemoryPressure.NORMAL,
        )
        val liveForThousand = policy.maxManagedLiveRenderers(
            logicalTabCount = 1_000,
            mode = RendererMemoryMode.BALANCED,
            pressure = RendererMemoryPressure.NORMAL,
        )

        assertEquals(5, liveForHundred)
        assertEquals(liveForHundred, liveForThousand)
        assertTrue(liveForHundred < 100)
    }

    @Test
    fun criticalPressureDropsVoluntaryWarmBudgetToZero() {
        assertEquals(
            0,
            policy.warmBudget(RendererMemoryMode.KEEP_MORE, RendererMemoryPressure.CRITICAL),
        )
        assertEquals(
            1,
            policy.maxManagedLiveRenderers(
                logicalTabCount = 100,
                mode = RendererMemoryMode.KEEP_MORE,
                pressure = RendererMemoryPressure.CRITICAL,
            ),
        )
    }

    @Test
    fun keepMoreAndSaveMemoryAreDistinctPolicies() {
        val save = policy.warmBudget(RendererMemoryMode.SAVE_MEMORY, RendererMemoryPressure.NORMAL)
        val balanced = policy.warmBudget(RendererMemoryMode.BALANCED, RendererMemoryPressure.NORMAL)
        val keep = policy.warmBudget(RendererMemoryMode.KEEP_MORE, RendererMemoryPressure.NORMAL)

        assertTrue(save < balanced)
        assertTrue(balanced < keep)
    }

    @Test
    fun uiHiddenIsModeratePressureNotCritical() {
        assertEquals(
            RendererMemoryPressure.MODERATE,
            RendererMemoryPolicy.pressureForTrimLevel(20),
        )
    }
}
