package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class GlassPaletteTest {
    @Test fun allBaseTokensAreNeutralNotBlueOrGreen() {
        listOf(GlassPalette.BACKGROUND,GlassPalette.SURFACE,GlassPalette.RAISED,GlassPalette.ACCENT,
            GlassPalette.ACTIVE,GlassPalette.TEXT,GlassPalette.MUTED,GlassPalette.EDGE,GlassPalette.RIPPLE,
            GlassPalette.TOP,GlassPalette.MIDDLE,GlassPalette.BOTTOM).forEach { color ->
            assertEquals((color ushr 16) and 255,(color ushr 8) and 255)
            assertEquals((color ushr 8) and 255,color and 255)
        }
    }
    @Test fun glassIsActuallyTranslucentAndTextRemainsOpaque() {
        assertTrue((GlassPalette.TOP ushr 24) in 140..220)
        assertTrue((GlassPalette.SURFACE ushr 24) in 140..220)
        assertTrue((GlassPalette.RAISED ushr 24) in 160..230)
        assertEquals(255,GlassPalette.TEXT ushr 24)
        assertEquals(255,GlassPalette.MUTED ushr 24)
        assertTrue((GlassPalette.TEXT and 255) - (GlassPalette.RAISED and 255) > 170)
        assertTrue((GlassPalette.MUTED and 255) - (GlassPalette.RAISED and 255) > 120)
    }
}
