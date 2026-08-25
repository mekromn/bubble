package com.mekromn.bubble.heads.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadPlacementMathTest {
    @Test
    fun clamp_keepsHeadReachable() {
        assertEquals(
            PixelPoint(900, 1800),
            HeadPlacementMath.clamp(
                point = PixelPoint(1400, 2600),
                containerWidth = 1000,
                containerHeight = 1900,
                itemWidth = 100,
                itemHeight = 100,
            ),
        )
    }

    @Test
    fun normalizeAndRestore_preservesIntentAcrossSizes() {
        val normalized = HeadPlacementMath.normalize(
            point = PixelPoint(450, 900),
            containerWidth = 1000,
            containerHeight = 1900,
            itemWidth = 100,
            itemHeight = 100,
        )
        val restored = HeadPlacementMath.denormalize(
            point = normalized,
            containerWidth = 2000,
            containerHeight = 3800,
            itemWidth = 200,
            itemHeight = 200,
        )
        assertEquals(PixelPoint(900, 1800), restored)
    }
}
