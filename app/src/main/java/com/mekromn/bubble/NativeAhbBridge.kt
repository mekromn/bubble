package com.mekromn.bubble

import android.view.Surface
import android.view.SurfaceControl

/**
 * JNI bridge for the Android-16-only ANativeWindow + AHardwareBuffer renderer experiment.
 *
 * The native side owns an AImageReader. Gecko renders into the reader's ANativeWindow; the consumer
 * side acquires the same frame as an AHardwareBuffer and submits that exact buffer to SurfaceFlinger
 * through ASurfaceControl. No CPU lock/readback or pixel copy is permitted in this path.
 */
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

    external fun nativeSetFrameRate(handle: Long, frameRate: Float)

    external fun nativeDestroy(handle: Long)
}
