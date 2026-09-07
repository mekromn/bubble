package com.mekromn.bubble

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import kotlin.math.abs

/**
 * Address field that keeps normal tap/edit behavior but recognizes deliberate horizontal swipes.
 * A swipe is only armed after touch slop and strong horizontal dominance, so vertical page gestures
 * and ordinary address taps are not stolen.
 */
internal class SwipeAddressEditText(context: Context) : EditText(context) {
    var onToolbarSwipe: ((ToolbarSwipe) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    private var armed = ToolbarSwipe.NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; swiping = false; armed = ToolbarSwipe.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val next = ToolbarSwipePolicy.classify(dx, dy, maxOf(slop * 2f, 28f), horizontalTabs = true)
                if (next != ToolbarSwipe.NONE && abs(dx) > abs(dy) * 1.15f) {
                    if (!swiping) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    armed = next
                    translationX = (dx * .12f).coerceIn(-24f, 24f)
                    alpha = .86f
                    return true
                }
                if (swiping) return true
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                val dx = event.x - startX
                val dy = event.y - startY
                val accepted = ToolbarSwipePolicy.classify(dx, dy, maxOf(slop * 2f, 28f), horizontalTabs = true)
                animate().cancel(); animate().translationX(0f).alpha(1f).setDuration(110).start()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (accepted == ToolbarSwipe.NEXT_TAB || accepted == ToolbarSwipe.PREVIOUS_TAB) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToolbarSwipe?.invoke(accepted)
                }
                swiping = false; armed = ToolbarSwipe.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (swiping) {
                animate().cancel(); animate().translationX(0f).alpha(1f).setDuration(110).start()
                parent?.requestDisallowInterceptTouchEvent(false)
                swiping = false; armed = ToolbarSwipe.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
