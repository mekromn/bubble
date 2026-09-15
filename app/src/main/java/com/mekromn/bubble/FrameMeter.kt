package com.mekromn.bubble

import android.app.Activity
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/**
 * User-triggered local efficiency ledger. It is completely dormant until Local frame measurements
 * is explicitly started, and it never uploads anything. Native window timings are NOT Gecko
 * compositor or website JavaScript FPS; CPU/memory/thermal/current values are contextual diagnostics.
 */
internal class FrameMeter {
    private val lock = Any()
    private val samples = LongArray(2048)
    private var index = 0
    private var count = 0L
    private var misses = 0L
    private var lost = 0L
    private var thread: HandlerThread? = null
    private var attached: Window? = null
    private var startElapsedMs = 0L
    private var startCpuMs = 0L
    private var startGcCount: Long? = null
    private var startAllocated: Long? = null
    @Volatile private var budget = 8_333_333L
    private val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
        if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) {
            val duration = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.DEADLINE) else budget
            if (duration > 0) synchronized(lock) {
                samples[index] = duration; index = (index + 1) % samples.size; count++
                if (duration > (deadline.takeIf { it > 0 } ?: budget)) misses++
                lost += dropped
            }
        }
    }
    fun start(activity: Activity) {
        if (thread != null) return
        synchronized(lock) {
            index = 0; count = 0; misses = 0; lost = 0
        }
        startElapsedMs = SystemClock.elapsedRealtime()
        startCpuMs = Process.getElapsedCpuTime()
        startGcCount = runtimeStat("art.gc.gc-count")
        startAllocated = runtimeStat("art.gc.bytes-allocated")
        val rate = Refresh.actual(activity)
        budget = (1_000_000_000.0 / rate.coerceAtLeast(1f)).toLong()
        val worker = HandlerThread("Bubble-local-frame-metrics").apply { start() }
        thread = worker; attached = activity.window
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
    }
    fun stop() {
        attached?.removeOnFrameMetricsAvailableListener(listener); attached = null
        thread?.quitSafely(); thread = null
    }
    fun report(activity: Activity): String = synchronized(lock) {
        val n = minOf(count, samples.size.toLong()).toInt()
        val sorted = samples.copyOf(n).sorted()
        val p95 = if (n == 0) 0.0 else sorted[((n - 1) * 0.95).toInt()] / 1_000_000.0
        val elapsedMs = (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(1L)
        val cpuMs = (Process.getElapsedCpuTime() - startCpuMs).coerceAtLeast(0L)
        // Process CPU time can exceed wall time on multicore work; report it rather than pretending
        // this is a calibrated battery-power percentage.
        val cpuWallPercent = cpuMs * 100.0 / elapsedMs
        val runtime = Runtime.getRuntime()
        val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val pssMb = Debug.getPss() / 1024.0
        val gcDelta = delta(runtimeStat("art.gc.gc-count"), startGcCount)
        val allocatedDelta = delta(runtimeStat("art.gc.bytes-allocated"), startAllocated)
        val allocatedMb = allocatedDelta?.div(1024.0 * 1024.0)
        val power = activity.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        val battery = activity.getSystemService(BatteryManager::class.java)
        val currentUa = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentText = if (currentUa == Long.MIN_VALUE) "unavailable" else "$currentUa µA raw"
        val gcText = gcDelta?.toString() ?: "unavailable"
        val allocText = allocatedMb?.let { String.format(Locale.US, "%.1f MB", it) } ?: "unavailable"
        val base = String.format(Locale.US,
            "Display now: %.1f Hz\nNative frames sampled: %d\nRecent native p95: %.2f ms\nDeadline misses: %d / %d\nLost callbacks: %d\n\nMeasurement wall time: %.1f s\nProcess CPU time: %d ms (%.1f%% of wall; multicore may exceed 100%%)\nJava heap used now: %.1f MB\nProcess PSS now: %.1f MB\nGC count delta: %s\nART allocated bytes delta: %s\nThermal status now: %d\nBattery current now: %s\n\nThese are Bubble/native-process diagnostics, not the webpage compositor's FPS or calibrated power consumption. Current sign/availability is device-defined. Android may lower refresh for heat, battery or system settings. No data leaves the device.",
            Refresh.actual(activity), count, p95, misses, count, lost,
            elapsedMs / 1000.0, cpuMs, cpuWallPercent, javaUsedMb, pssMb, gcText, allocText, thermal, currentText)
        base + "\n\n" + FloatingPerformancePolicy.diagnostics()
    }

    private fun runtimeStat(name: String): Long? = runCatching {
        Debug.getRuntimeStat(name)?.toLongOrNull()
    }.getOrNull()

    private fun delta(now: Long?, start: Long?): Long? =
        if (now == null || start == null) null else (now - start).coerceAtLeast(0L)
}

internal object Refresh {
    @Suppress("DEPRECATION") fun actual(a: Activity): Float = a.windowManager.defaultDisplay.refreshRate

    /**
     * Fullscreen uses the exact same maximum-refresh policy as floating mode. RenderPolicy walks the
     * entire decor tree, so Android 15+ per-View hints reach GeckoView's children and Android 11+
     * SurfaceView producers receive the same Surface.setFrameRate() contract as the raw floating page.
     */
    fun request(a: Activity) {
        val attributes = a.window.attributes
        RenderPolicy.vote(a, a.window.decorView, attributes)
        a.window.attributes = attributes
    }
}
