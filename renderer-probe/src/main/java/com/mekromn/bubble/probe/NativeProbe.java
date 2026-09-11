package com.mekromn.bubble.probe;

import android.view.Surface;
import android.view.SurfaceControl;

/** No native buffer acquisition, fence waiting, or teardown runs on the Android UI thread. */
final class NativeProbe {
    static { System.loadLibrary("bubble-probe"); }
    private NativeProbe() {}
    static native String capabilities(int width, int height);
    static native long create(int width, int height, SurfaceControl output, float rate,
                              int maxImages, int drainLimit, boolean backpressure);
    static native Surface surface(long handle);
    // This only flips an atomic measurement gate; it never touches the reader or a GPU fence.
    static native void measuring(long handle, boolean active);
    // Off-main only. Detaches output, drains leases, and returns measurements and teardown status.
    static native String finish(long handle);
}
