package com.mekromn.bubble

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/** Runtime-selectable input delivery for the physical windowed A/B arena. */
internal object PageTouchDispatch {
    /** Pure predicate also exercised on the JVM; Android constant values are inlined. */
    fun eligible(action: Int, source: Int, hasSession: Boolean): Boolean =
        hasSession && action == MotionEvent.ACTION_DOWN &&
            (source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN ||
                source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS)

    /** Build 139 = no request. Build 140/experimental = gesture-scoped unbuffering. */
    fun requestPage(view: View, event: MotionEvent, hasSession: Boolean) {
        if (!WindowedBenchmark.wantsPageUnbuffered()) return
        request(view, event, hasSession)
    }

    /** Experimental arm changes only Bubble's own window drag/resize stream. */
    fun requestChrome(view: View, event: MotionEvent) {
        if (!WindowedBenchmark.wantsChromeUnbuffered()) return
        request(view, event, true)
    }

    private fun request(view: View, event: MotionEvent, hasSession: Boolean) {
        if (eligible(event.actionMasked, event.source, hasSession) && view.isAttachedToWindow) {
            view.requestUnbufferedDispatch(event)
        }
    }
}
