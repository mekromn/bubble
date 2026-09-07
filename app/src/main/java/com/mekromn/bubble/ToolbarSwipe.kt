package com.mekromn.bubble

import kotlin.math.abs

internal enum class ToolbarSwipe {
    NONE,
    NEXT_TAB,
    PREVIOUS_TAB,
    OPEN_CHOOSER,
    MINIMIZE
}

/** Pure gesture classification shared by fullscreen's address pill and floating bottom pill. */
internal object ToolbarSwipePolicy {
    fun classify(
        dx: Float,
        dy: Float,
        threshold: Float,
        horizontalTabs: Boolean = false,
        swipeUpChooser: Boolean = false,
        swipeDownMinimize: Boolean = false
    ): ToolbarSwipe {
        val ax = abs(dx)
        val ay = abs(dy)
        if (horizontalTabs && ax > threshold && ax > ay * 1.15f) {
            return if (dx < 0f) ToolbarSwipe.NEXT_TAB else ToolbarSwipe.PREVIOUS_TAB
        }
        if (swipeUpChooser && -dy > threshold && -dy > ax * 1.15f) return ToolbarSwipe.OPEN_CHOOSER
        if (swipeDownMinimize && dy > threshold && dy > ax * 1.15f) return ToolbarSwipe.MINIMIZE
        return ToolbarSwipe.NONE
    }
}
