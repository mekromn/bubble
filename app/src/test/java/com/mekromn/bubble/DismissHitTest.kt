package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class DismissHitTest {
    @Test fun mustEnterTheTargetButDoesNotFlickerAtItsEdge() {
        assertFalse(DismissHit.contains(60f, 0f, 0f, 0f, 52f, 72f, false))
        assertTrue(DismissHit.contains(50f, 0f, 0f, 0f, 52f, 72f, false))
        assertTrue(DismissHit.contains(60f, 0f, 0f, 0f, 52f, 72f, true))
        assertFalse(DismissHit.contains(73f, 0f, 0f, 0f, 52f, 72f, true))
    }
    @Test fun diagonalDistanceAndInvalidCoordinatesCannotDismissAccidentally() {
        assertFalse(DismissHit.contains(40f, 40f, 0f, 0f, 52f, 72f, false))
        assertFalse(DismissHit.contains(Float.NaN, 0f, 0f, 0f, 52f, 72f, true))
        assertFalse(DismissHit.contains(0f, Float.POSITIVE_INFINITY, 0f, 0f, 52f, 72f, true))
    }
}
