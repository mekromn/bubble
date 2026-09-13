package com.mekromn.bubble

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Let Gecko's asynchronous input/compositor pipeline receive a page gesture without
 * Android deliberately retaining its pending moves for the View hierarchy's frame.
 *
 * This is a gesture-scoped request, not the persistent input-source overload. Android
 * restores batching on UP/CANCEL. Never enqueue, clone, retimestamp, split, synthesize
 * or consume the event here: the existing Gecko/APZ handler remains its sole owner.
 * Unbuffering can increase callback work; it is not a touch-to-photon speed guarantee.
 */
internal object PageTouchDispatch {
    /** Pure predicate also exercised on the JVM; Android constant values are inlined. */
    fun eligible(action: Int, source: Int, hasSession: Boolean): Boolean =
        hasSession && action == MotionEvent.ACTION_DOWN &&
            (source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN ||
                source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS)

    fun request(view: View, event: MotionEvent, hasSession: Boolean) {
        if (eligible(event.actionMasked, event.source, hasSession) && view.isAttachedToWindow) {
            // The exact event must be in normal Android dispatch when this is called.
            // No global setting, hidden API, log, allocation, timer or per-frame pump.
            view.requestUnbufferedDispatch(event)
        }
    }
}
