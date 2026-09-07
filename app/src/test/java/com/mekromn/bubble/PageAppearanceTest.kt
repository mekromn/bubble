package com.mekromn.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class PageAppearanceTest {
    @Test fun wireValuesAreStableAndUnknownFallsBackToSiteSystem() {
        assertEquals(PageAppearanceMode.DEFAULT, PageAppearanceMode.fromWire(null))
        assertEquals(PageAppearanceMode.DEFAULT, PageAppearanceMode.fromWire("default"))
        assertEquals(PageAppearanceMode.DARK, PageAppearanceMode.fromWire("dark"))
        assertEquals(PageAppearanceMode.LIGHT, PageAppearanceMode.fromWire("light"))
        assertEquals(PageAppearanceMode.DEFAULT, PageAppearanceMode.fromWire("future-value"))
    }

    @Test fun modesHaveDistinctPersistentWireValues() {
        assertEquals(3, PageAppearanceMode.entries.map { it.wire }.toSet().size)
    }
}
