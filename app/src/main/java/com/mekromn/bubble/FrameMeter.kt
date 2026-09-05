package com.mekromn.bubble

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.util.Locale

/** Native-window measurements only, NOT Gecko compositor or website JavaScript FPS. No uploads. */
internal class FrameMeter {
    private val lock = Any()
    private val samples = LongArray(2048)
    private var index = 0
    private var count = 0L
    private var misses = 0L
    private var lost = 0L
    private var thread: HandlerThread? = null
    private var attached: Window? = null
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
        String.format(Locale.US,
            "Display now: %.1f Hz\nNative frames sampled: %d\nRecent native p95: %.2f ms\nDeadline misses: %d / %d\nLost callbacks: %d\n\nThese are Bubble window timings, not the webpage compositor's FPS. Android may lower refresh for heat, battery or system settings. No data leaves the device.",
            Refresh.actual(activity), count, p95, misses, count, lost)
    }
}

internal object Refresh {
    @Suppress("DEPRECATION") fun actual(a: Activity): Float = a.windowManager.defaultDisplay.refreshRate
    @Suppress("DEPRECATION") fun request(a: Activity) {
        val display = a.windowManager.defaultDisplay
        val current = display.mode
        val best = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.maxByOrNull { it.refreshRate } ?: current
        val attributes = a.window.attributes
        if(attributes.preferredDisplayModeId != best.modeId || attributes.preferredRefreshRate != best.refreshRate) {
            attributes.preferredDisplayModeId = best.modeId
            attributes.preferredRefreshRate = best.refreshRate
            a.window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= 35) vote(a.window.decorView, best.refreshRate)
    }
    private fun vote(view: View, rate: Float) {
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate)
        if (view is ViewGroup) for (i in 0 until view.childCount) vote(view.getChildAt(i), rate)
    }
}
