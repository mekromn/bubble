package com.mekromn.bubble.display

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    private val rates = listOf(30f, 60f, 90f, 120f, 144f)

    @Test
    fun default120PlusSelectsHighestRateAtOrAbove120() {
        assertEquals(
            144f,
            RefreshRatePolicy.resolveSupportedRate(RefreshRateMode.HZ_120_PLUS, rates),
            0.001f,
        )
    }

    @Test
    fun default120PlusFallsBackToHighestOnSlowerDisplay() {
        assertEquals(
            90f,
            RefreshRatePolicy.resolveSupportedRate(
                RefreshRateMode.HZ_120_PLUS,
                listOf(60f, 90f),
            ),
            0.001f,
        )
    }

    @Test
    fun explicitTargetsUseNearestSupportedRate() {
        assertEquals(
            60f,
            RefreshRatePolicy.resolveSupportedRate(
                RefreshRateMode.HZ_60,
                listOf(59.94f, 60f, 120f),
            ),
            0.001f,
        )
        assertEquals(
            90f,
            RefreshRatePolicy.resolveSupportedRate(RefreshRateMode.HZ_90, rates),
            0.001f,
        )
    }

    @Test
    fun autoClearsExplicitRefreshPreference() {
        assertEquals(
            0f,
            RefreshRatePolicy.resolveSupportedRate(RefreshRateMode.AUTO, rates),
            0.001f,
        )
    }
}
