package com.mekromn.bubble

import android.view.Surface
import android.view.SurfaceControl

/** JNI bridge for the Android-16-only ANativeWindow + AHardwareBuffer renderer experiment. */
internal object NativeAhbBridge {
    init {
        System.loadLibrary("bubble-ahb")
    }

    external fun nativeCreate(
        width: Int,
        height: Int,
        outputControl: SurfaceControl,
        frameRate: Float
    ): Long

    external fun nativeGetProducerSurface(handle: Long): Surface?

    /**
     * Poll the shared AImageReader consumer even when auto-refresh produced no ordinary frame callback.
     * Positive = cumulative AHardwareBuffer submissions; 0 = no buffer; negative = forensic error code.
     */
    external fun nativePump(handle: Long): Int

    external fun nativeSetFrameRate(handle: Long, frameRate: Float)

    external fun nativeDestroy(handle: Long)
}
