package com.mekromn.bubble

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Runtime-selectable input policy for the production A/B build.
 *
 * BUFFERED_139 is the exact Build-139 behavior: no unbuffered request.
 * UNBUFFERED_140 is Build 140: request unbuffered delivery for the current
 * touchscreen/stylus gesture on ACTION_DOWN. STYLUS_ONLY is an additional
 * conservative arena candidate: keep Android's normal finger resampling while
 * requesting immediate delivery only for stylus streams.
 *
 * The existing Gecko/APZ handler remains sole owner of the original MotionEvent.
 */
internal object PageTouchDispatch {
    enum class Arm(val shortLabel: String) {
        BUFFERED_139("139 buffered"),
        UNBUFFERED_140("140 unbuffered"),
        STYLUS_ONLY("adaptive stylus-only")
    }

    @Volatile var arm: Arm = Arm.UNBUFFERED_140

    /** Pure predicate also exercised on the JVM; Android constant values are inlined. */
    fun eligible(action: Int, source: Int, hasSession: Boolean): Boolean =
        hasSession && action == MotionEvent.ACTION_DOWN &&
            (source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN ||
                source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS)

    fun request(view: View, event: MotionEvent, hasSession: Boolean) {
        if (!eligible(event.actionMasked, event.source, hasSession) || !view.isAttachedToWindow) return
        val shouldUnbuffer = when (arm) {
            Arm.BUFFERED_139 -> false
            Arm.UNBUFFERED_140 -> true
            Arm.STYLUS_ONLY -> event.source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
        }
        if (shouldUnbuffer) {
            // Gesture-scoped public API. No event copy/queue/retimestamp/synthesis.
            view.requestUnbufferedDispatch(event)
        }
    }
}
