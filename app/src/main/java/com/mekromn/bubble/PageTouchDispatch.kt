package com.mekromn.bubble

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Runtime-selectable input policy for the production renderer arena.
 * BUFFERED_139 keeps Android's normal batching/resampling. UNBUFFERED_140 asks
 * Android for gesture-scoped unbuffered delivery on ACTION_DOWN. STYLUS_ONLY is
 * retained as an additional conservative candidate.
 *
 * The existing Gecko/APZ handler remains sole owner of the original MotionEvent.
 * Build 145's physical renderer benchmark surrounds only this identical pre-Gecko
 * input-policy preamble in both Direct and Relay. Outside a measured run the
 * recorder immediately returns and changes no event or renderer state.
 *
 * Android 16 floating direct mode also sends one ADPF workload-increase hint on
 * ACTION_DOWN. There is no hint/JNI traffic for MOVE events or rendered frames.
 */
internal object PageTouchDispatch {
    enum class Arm(val shortLabel: String) {
        BUFFERED_139("139 buffered"),
        UNBUFFERED_140("140 unbuffered"),
        STYLUS_ONLY("adaptive stylus-only")
    }

    @Volatile var arm: Arm = Arm.UNBUFFERED_140

    fun eligible(action: Int, source: Int, hasSession: Boolean): Boolean =
        hasSession && action == MotionEvent.ACTION_DOWN &&
            (source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN ||
                source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS)

    fun shouldUnbuffer(selected: Arm, source: Int): Boolean = when (selected) {
        Arm.BUFFERED_139 -> false
        Arm.UNBUFFERED_140 -> true
        Arm.STYLUS_ONLY -> source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
    }

    fun request(view: View, event: MotionEvent, hasSession: Boolean) {
        val rendererBenchmark = RendererBenchmark.beforePage(view, event)
        val pinchBenchmark = PinchBenchmark.beforePage(view, event)
        try {
            if (!eligible(event.actionMasked, event.source, hasSession) || !view.isAttachedToWindow) return
            if (Workspace.peek()?.floatingVisible == true) FloatingPerformancePolicy.interactionStart()
            if (shouldUnbuffer(arm, event.source)) view.requestUnbufferedDispatch(event)
        } finally {
            RendererBenchmark.afterPage(rendererBenchmark, event)
            PinchBenchmark.afterPage(pinchBenchmark, event)
        }
    }
}
