package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class EdgePolicyTest {
    @Test fun inwardDirectionIsMirroredAndUnambiguous() {
        assertTrue(EdgePolicy.opens(40f, 2f, 500, true, 32f))
        assertTrue(EdgePolicy.opens(-40f, 2f, 500, false, 32f))
        assertFalse(EdgePolicy.opens(40f, 0f, 500, false, 32f))
        assertFalse(EdgePolicy.opens(31f, 0f, 500, true, 32f))
        assertFalse(EdgePolicy.opens(40f, 40f, 500, true, 32f))
        assertFalse(EdgePolicy.opens(40f, 0f, 1601, true, 32f))
        assertFalse(EdgePolicy.opens(Float.NaN, 0f, 500, true, 32f))
    }
    @Test fun verticalOrInvalidGesturesCannotBecomeAnOpen() {
        assertTrue(EdgePolicy.vertical(3f, 40f, 8f))
        assertFalse(EdgePolicy.vertical(40f, 3f, 8f))
        assertFalse(EdgePolicy.opens(100f, Float.NaN, 100, true, 32f))
        assertFalse(EdgePolicy.opens(100f, 0f, -1, true, 32f))
    }
    @Test fun preferencesAreBoundedAndOptIn() {
        assertFalse(EdgeOptions().enabled)
        val fixed = EdgeOptions(position = Float.NaN, heightDp = 9000, widthDp = -1, bubbleOpacity = Float.NaN).sanitized()
        assertEquals(.5f, fixed.position, .0001f)
        assertEquals(160, fixed.heightDp); assertEquals(12, fixed.widthDp)
        assertEquals(.90f, fixed.bubbleOpacity, .0001f)
        assertEquals(0f, EdgeOptions(position = -100f).sanitized().position, 0f)
        assertEquals(1f, EdgeOptions(position = 100f).sanitized().position, 0f)
        assertEquals(.12f, EdgeOptions(bubbleOpacity = -9f).sanitized().bubbleOpacity, 0f)
        assertEquals(1f, EdgeOptions(bubbleOpacity = 9f).sanitized().bubbleOpacity, 0f)
    }
}
