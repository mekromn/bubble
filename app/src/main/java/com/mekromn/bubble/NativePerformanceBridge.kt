package com.mekromn.bubble

import android.view.Surface

/**
 * Android 16 ADPF bridge for the floating direct Gecko SurfaceView.
 *
 * The native session is surface-bound and uses automatic CPU/GPU timing when the device exposes
 * those features. There is no Java/JNI callback per webpage frame. Bubble only sends one workload
 * increase hint at the start of a touch gesture so the framework can anticipate the scroll/fling.
 */
internal object NativePerformanceBridge {
    init { System.loadLibrary("bubble-ahb") }

    external fun nativeStart(surface: Surface, frameRate: Float, callerTid: Int): Long
    external fun nativeNotifyInteraction(handle: Long)
    external fun nativeStop(handle: Long)
    /** [featureBits, createStatus, graphicsThreadCount, interactionHints]. */
    external fun nativeStatus(handle: Long): IntArray
}
