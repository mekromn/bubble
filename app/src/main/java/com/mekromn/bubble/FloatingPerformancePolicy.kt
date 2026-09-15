package com.mekromn.bubble

import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Locale

/**
 * Floating mode is not Android's top-activity scheduling class. On Android 16, compensate through
 * the public Android Dynamic Performance Framework instead of hidden scheduler APIs: associate the
 * real Gecko SurfaceView producer with an ADPF graphics-pipeline session and let the system perform
 * automatic CPU/GPU timing. The policy is created once per Surface lifetime and is completely
 * callback-free for normal frame production.
 *
 * Native session setup enumerates /proc thread names to identify the graphics critical path, so that
 * setup runs on a short-lived worker rather than Android's main thread. A generation token closes a
 * session immediately if its Surface is replaced while setup is in flight.
 */
internal object FloatingPerformancePolicy {
    private const val BIT_SESSIONS = 1 shl 0
    private const val BIT_GRAPHICS = 1 shl 1
    private const val BIT_SURFACE = 1 shl 2
    private const val BIT_AUTO_CPU = 1 shl 3
    private const val BIT_AUTO_GPU = 1 shl 4
    private const val BIT_CREATED = 1 shl 5

    private val main = Handler(Looper.getMainLooper())
    private var bound: SurfaceView? = null
    private var callback: SurfaceHolder.Callback? = null
    private var requestedRate = 0f
    private var generation = 0
    private var startPending = false
    @Volatile private var handle = 0L
    @Volatile private var lastFeatureBits = 0
    @Volatile private var lastCreateStatus = 0
    @Volatile private var lastThreadCount = 0
    @Volatile private var interactionHints = 0
    @Volatile private var lastDisplayRate = 0f
    @Volatile private var lastImportance = 0

    fun bind(target: SurfaceView, rate: Float) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 36 || rate <= 0f) return
        if (bound === target && callback != null) {
            requestedRate = rate
            lastDisplayRate = target.display?.refreshRate ?: lastDisplayRate
            if (handle == 0L && !startPending && target.holder.surface.isValid) start(target)
            return
        }
        unbind()
        bound = target
        requestedRate = rate
        lastDisplayRate = target.display?.refreshRate ?: 0f
        captureImportance()
        val listener = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (bound === target && holder.surface.isValid) start(target)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (bound === target && handle == 0L && !startPending && holder.surface.isValid) start(target)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (bound === target) stopHandle()
            }
        }
        callback = listener
        target.holder.addCallback(listener)
        if (target.holder.surface.isValid) start(target)
    }

    fun unbind() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = bound
        val listener = callback
        if (target != null && listener != null) runCatching { target.holder.removeCallback(listener) }
        callback = null
        bound = null
        stopHandle()
    }

    /** One low-frequency hint at gesture start; never called for individual rendered frames. */
    fun interactionStart() {
        if (Build.VERSION.SDK_INT < 36 || Workspace.peek()?.floatingVisible != true) return
        val current = handle
        if (current == 0L) return
        interactionHints++
        lastDisplayRate = bound?.display?.refreshRate ?: lastDisplayRate
        // Do not query ActivityManager here: this is the page ACTION_DOWN hot path.
        runCatching { NativePerformanceBridge.nativeNotifyInteraction(current) }
    }

    fun diagnostics(): String {
        val bits = lastFeatureBits
        val features = buildList {
            if (bits and BIT_SESSIONS != 0) add("sessions")
            if (bits and BIT_GRAPHICS != 0) add("graphics")
            if (bits and BIT_SURFACE != 0) add("surface")
            if (bits and BIT_AUTO_CPU != 0) add("autoCPU")
            if (bits and BIT_AUTO_GPU != 0) add("autoGPU")
            if (bits and BIT_CREATED != 0) add("created")
        }.joinToString(", ").ifBlank { "none" }
        return String.format(
            Locale.US,
            "Floating ADPF: %s\nADPF create status: %d · hinted threads: %d\nFloating display observed: %.1f Hz · gesture hints: %d\nFloating process importance observed: %d",
            features, lastCreateStatus, lastThreadCount, lastDisplayRate, interactionHints, lastImportance
        )
    }

    private fun start(target: SurfaceView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stopHandle()
        if (!target.holder.surface.isValid || requestedRate <= 0f || bound !== target) return
        lastDisplayRate = target.display?.refreshRate ?: lastDisplayRate
        captureImportance()
        val rate = requestedRate
        val mainTid = Process.myTid()
        val surface = target.holder.surface
        val ticket = ++generation
        startPending = true
        Thread({
            val created = runCatching {
                NativePerformanceBridge.nativeStart(surface, rate, mainTid)
            }.getOrDefault(0L)
            val state = runCatching { NativePerformanceBridge.nativeStatus(created) }.getOrNull()
            main.post {
                if (generation == ticket && bound === target && target.holder.surface.isValid) {
                    startPending = false
                    handle = created
                    applyNativeStatus(state)
                } else {
                    if (created != 0L) Thread({
                        runCatching { NativePerformanceBridge.nativeStop(created) }
                    }, "Bubble-floating-ADPF-retire").start()
                }
            }
        }, "Bubble-floating-ADPF-setup").start()
    }

    private fun stopHandle() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        startPending = false
        val current = handle
        handle = 0L
        if (current == 0L) return
        Thread({
            val state = runCatching { NativePerformanceBridge.nativeStatus(current) }.getOrNull()
            runCatching { NativePerformanceBridge.nativeStop(current) }
            main.post { applyNativeStatus(state) }
        }, "Bubble-floating-ADPF-stop").start()
    }

    private fun applyNativeStatus(state: IntArray?) {
        if (state == null || state.size < 4) return
        lastFeatureBits = state[0]
        lastCreateStatus = state[1]
        lastThreadCount = state[2]
        interactionHints = maxOf(interactionHints, state[3])
    }

    private fun captureImportance() {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        lastImportance = info.importance
    }
}
