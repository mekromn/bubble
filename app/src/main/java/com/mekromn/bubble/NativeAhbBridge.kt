package com.mekromn.bubble

import android.view.Surface
import android.view.SurfaceControl

/** Android 16 relay_latest_bp: 6 acquired images, bounded drain 4, one native worker. */
internal object NativeAhbBridge {
    init { System.loadLibrary("bubble-ahb") }
    /** Creation can allocate buffers/connect Binder. Call only on the creation executor. */
    external fun nativeCreate(width: Int, height: Int, outputControl: SurfaceControl, frameRate: Float): Long
    external fun nativeGetProducerSurface(handle: Long): Surface?
    /** Sets an atomic request; the consumer applies it off-main. */
    external fun nativeSetFrameRate(handle: Long, frameRate: Float)
    /** Invalidates the handle and wakes async cleanup. Does not join/wait/acquire on the UI thread. */
    external fun nativeDestroy(handle: Long)
    /** On-demand integration diagnostics only; never polled by the production rendering loop. */
    external fun nativeDebugStats(): LongArray
}
