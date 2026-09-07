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

    @Test fun floatingPillSeparatesUpChooserFromDownMinimize() {
        assertEquals(ToolbarSwipe.OPEN_CHOOSER, ToolbarSwipePolicy.classify(5f, -70f, 32f, swipeUpChooser = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.MINIMIZE, ToolbarSwipePolicy.classify(-6f, 70f, 32f, swipeUpChooser = true, swipeDownMinimize = true))
        assertEquals(ToolbarSwipe.NONE, ToolbarSwipePolicy.classify(50f, -50f, 32f, swipeUpChooser = true, swipeDownMinimize = true))
    }
}
