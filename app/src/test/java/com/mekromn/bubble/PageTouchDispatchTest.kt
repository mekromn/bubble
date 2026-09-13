package com.mekromn.bubble

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class PageTouchDispatchTest {
    @Test fun touchscreenAndStylusStartOnly() {
        for (source in intArrayOf(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_STYLUS,
            InputDevice.SOURCE_BLUETOOTH_STYLUS)) {
            assertTrue(PageTouchDispatch.eligible(MotionEvent.ACTION_DOWN, source, true))
            assertFalse(PageTouchDispatch.eligible(MotionEvent.ACTION_DOWN, source, false))
            for (action in intArrayOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_SCROLL)) {
                assertFalse("No repeat or persistent request for action=$action", PageTouchDispatch.eligible(action, source, true))
            }
        }
    }
    @Test fun unrelatedInputSourcesKeepTheirNormalPath() {
        for (source in intArrayOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE_RELATIVE,
            InputDevice.SOURCE_TOUCHPAD, InputDevice.SOURCE_JOYSTICK,
            InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_UNKNOWN)) {
            assertFalse("Do not change unrelated source=$source", PageTouchDispatch.eligible(MotionEvent.ACTION_DOWN, source, true))
        }
    }
}
