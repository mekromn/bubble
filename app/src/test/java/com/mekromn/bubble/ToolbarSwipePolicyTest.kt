package com.mekromn.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarSwipePolicyTest {
    @Test fun fullscreenHorizontalSwipesCycleTabsOnlyWhenDominant() {
        assertEquals(ToolbarSwipe.NEXT_TAB, ToolbarSwipePolicy.classify(-80f, 8f, 32f, horizontalTabs = true))
        assertEquals(ToolbarSwipe.PREVIOUS_TAB, ToolbarSwipePolicy.classify(80f, -8f, 32f, horizontalTabs = true))
        assertEquals(ToolbarSwipe.NONE, ToolbarSwipePolicy.classify(30f, 3f, 32f, horizontalTabs = true))
        assertEquals(ToolbarSwipe.NONE, ToolbarSwipePolicy.classify(80f, 76f, 32f, horizontalTabs = true))
    }

    @Test fun floatingPagePillMapsAllFourDirections() {
        assertEquals(ToolbarSwipe.PAGE_BACK, ToolbarSwipePolicy.classify(-72f, 5f, 24f, horizontalHistory = true, swipeUpChooser = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.PAGE_FORWARD, ToolbarSwipePolicy.classify(72f, -5f, 24f, horizontalHistory = true, swipeUpChooser = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.OPEN_CHOOSER, ToolbarSwipePolicy.classify(5f, -70f, 24f, horizontalHistory = true, swipeUpChooser = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.MINIMIZE, ToolbarSwipePolicy.classify(-6f, 70f, 24f, horizontalHistory = true, swipeUpChooser = true, swipeDownMinimize = true))
    }

    @Test fun chooserPillReturnsUpAndMinimizesDown() {
        assertEquals(ToolbarSwipe.RETURN_TO_TAB, ToolbarSwipePolicy.classify(-8f, -64f, 24f, swipeUpReturn = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.MINIMIZE, ToolbarSwipePolicy.classify(6f, 64f, 24f, swipeUpReturn = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.NONE, ToolbarSwipePolicy.classify(80f, -40f, 24f, swipeUpReturn = true, swipeDownMinimize = true))
    }
}
