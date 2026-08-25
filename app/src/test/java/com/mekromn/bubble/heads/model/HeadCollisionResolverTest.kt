package com.mekromn.bubble.heads.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeadCollisionResolverTest {
    @Test
    fun keepsPreferredPlacementWhenItDoesNotOverlap() {
        val preferred = PixelPoint(240, 120)
        val result = HeadCollisionResolver.resolve(
            preferred = preferred,
            occupied = listOf(PixelPoint(20, 20)),
            areaWidth = 400,
            areaHeight = 800,
            headWidth = 58,
            headHeight = 58,
            gap = 10,
        )
        assertEquals(preferred, result)
    }

    @Test
    fun movesRestoredHeadAwayFromOccupiedPosition() {
        val occupied = PixelPoint(320, 80)
        val result = HeadCollisionResolver.resolve(
            preferred = occupied,
            occupied = listOf(occupied),
            areaWidth = 400,
            areaHeight = 800,
            headWidth = 58,
            headHeight = 58,
            gap = 10,
        )
        assertFalse(
            HeadCollisionResolver.overlaps(
                result,
                occupied,
                width = 58,
                height = 58,
                gap = 10,
            ),
        )
    }

    @Test
    fun clampsInvalidStoredPlacementInsideDisplay() {
        val result = HeadCollisionResolver.resolve(
            preferred = PixelPoint(900, -200),
            occupied = emptyList(),
            areaWidth = 400,
            areaHeight = 800,
            headWidth = 58,
            headHeight = 58,
            gap = 10,
        )
        assertEquals(PixelPoint(342, 0), result)
    }
}
