package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class WindowGeometryTest {
    @Test fun floatingWindowsRemainInsideSafeInsets() {
        val safe = WindowBox(12, 40, 380, 780)
        assertEquals(WindowBox(12, 40, 380, 780), WindowGeometry.fit(WindowBox(-100, -90, 900, 900), safe))
        assertEquals(WindowBox(328, 756, 64, 64), WindowGeometry.placed(safe, 1f, 1f, 64, 64))
        assertEquals(WindowBox(12, 40, 64, 64), WindowGeometry.placed(safe, -5f, -9f, 64, 64))
    }
    @Test fun invalidSavedCoordinatesDoNotStrandTheWindow() {
        val safe = WindowBox(0, 20, 100, 300)
        assertEquals(WindowBox(0, 65, 100, 150), WindowGeometry.placed(safe, Float.NaN, Float.POSITIVE_INFINITY, 400, 150))
        val tiny = WindowGeometry.fit(WindowBox(99, 99, -2, -9), WindowBox(0, 0, 1, 1))
        assertEquals(WindowBox(0, 0, 1, 1), tiny)
    }
    @Test fun rotationAndResizingClampWithoutChangingTabIdentity() {
        val portrait = WindowGeometry.placed(WindowBox(0, 30, 400, 800), .9f, .8f, 360, 580)
        val landscape = WindowGeometry.fit(portrait, WindowBox(30, 0, 780, 360))
        assertEquals(360, landscape.height)
        assertTrue(landscape.x >= 30)
        assertTrue(landscape.x + landscape.width <= 810)
    }
}
