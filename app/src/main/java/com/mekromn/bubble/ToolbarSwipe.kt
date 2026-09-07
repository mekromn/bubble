package com.mekromn.bubble

import kotlin.math.abs

internal enum class ToolbarSwipe {
    NONE,
    NEXT_TAB,
    PREVIOUS_TAB,
    OPEN_CHOOSER,
    RETURN_TO_TAB,
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
        swipeUpReturn: Boolean = false,
        swipeDownMinimize: Boolean = false
    ): ToolbarSwipe {
        val ax = abs(dx)
        val ay = abs(dy)
        if (horizontalTabs && ax > threshold && ax > ay * 1.15f) {
            return if (dx < 0f) ToolbarSwipe.NEXT_TAB else ToolbarSwipe.PREVIOUS_TAB
        }
        // Floating-window pills are deliberately forgiving. A user's thumb does not have to travel
        // perfectly vertically; once vertical travel is materially larger than horizontal drift,
        // the gesture is accepted. This also makes short Pixel high-refresh swipes reliable.
        val verticalDominant = ay > ax * .72f
        if (verticalDominant && -dy > threshold) {
            if (swipeUpReturn) return ToolbarSwipe.RETURN_TO_TAB
            if (swipeUpChooser) return ToolbarSwipe.OPEN_CHOOSER
        }
        if (verticalDominant && swipeDownMinimize && dy > threshold) return ToolbarSwipe.MINIMIZE
        return ToolbarSwipe.NONE
    }
}
