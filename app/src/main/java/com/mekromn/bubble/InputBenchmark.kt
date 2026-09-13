package com.mekromn.bubble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * On-device A/B recorder for the 139 buffered-input policy versus 140's
 * gesture-scoped unbuffered policy. It is dormant unless the benchmark launcher
 * starts a block. The actual BrowserActivity/FloatingGeckoWindow and renderer are
 * used; there is no benchmark-only page host.
 *
 * Statistical unit = a counterbalanced PAIR of blocks, never individual frames.
 * Root Choreographer cadence is deliberately labelled UI-thread cadence, not
 * webpage presentation timing. Native relay counters are likewise lifecycle/
 * throughput observations, not touch-to-photon timestamps.
 */
internal object InputBenchmark {
    const val HOST_FULLSCREEN = 0
    const val HOST_FLOATING = 1
    const val HOST_ANY = 2

    private const val MAX_TOUCH = 8192
    private const val MAX_FRAMES = 4096
    private const val SETTLE_MS = 2500L
    private const val BETWEEN_MS = 1500L
    private const val DEFAULT_BLOCK_MS = 12_000L
    private const val DEFAULT_PAIRS = 8

    data class BlockResult(
        val runId: String,
        val pair: Int,
        val order: Int,
        val arm: PageTouchDispatch.Arm,
        val expectedHost: Int,
        val dominantHost: Int,
        val durationMs: Long,
        val touchCount: Int,
        val moveCount: Int,
        val historySamples: Int,
        val overflow: Int,
        val eventAgeP50Ms: Double,
        val eventAgeP95Ms: Double,
        val geckoCallP50Ms: Double,
        val geckoCallP95Ms: Double,
        val moveGapP50Ms: Double,
        val moveGapP95Ms: Double,
        val uiFrameP50Ms: Double,
        val uiFrameP95Ms: Double,
        val uiDeadlineMissPct: Double,
        val displayHz: Double,
        val processCpuPct: Double,
        val pssDeltaKb: Long,
        val thermalStart: Int,
        val thermalEnd: Int,
        val batteryCurrentUa: Int,
        val energyDeltaNwh: Long,
        val nativeSubmitted: Long,
        val nativeReleased: Long,
        val nativeErrors: Long,
        val pageDeliveryP50Ms: Double?,
        val pageDeliveryP95Ms: Double?,
        val pageRafP95Ms: Double?,
        val pageEventDurationP95Ms: Double?,
        val pageMoves: Int,
        val pageCoalesced: Int,
        val pageScrollEvents: Int,
        val pageLongTasks: Int,
        val valid: Boolean,
        val quality: String
    )

    private data class Spec(val pair: Int, val order: Int, val arm: PageTouchDispatch.Arm)
    private data class PageSnapshot(
        val delivery: DoubleArray,
        val raf: DoubleArray,
        val eventDuration: DoubleArray,
        val moves: Int,
        val coalesced: Int,
        val scrolls: Int,
        val longTasks: Int
    )

    @Volatile var measuring: Boolean = false
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile var blockId: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var expectedHost = HOST_ANY
    private var blockMs = DEFAULT_BLOCK_MS
    private var runId = ""
    private var specs: List<Spec> = emptyList()
    private var specIndex = 0
    private val results = ArrayList<BlockResult>()

    private val eventAgeUs = LongArray(MAX_TOUCH)
    private val geckoCallUs = LongArray(MAX_TOUCH)
    private val geckoStartNs = LongArray(MAX_TOUCH)
    private val moveGapUs = LongArray(MAX_TOUCH)
    private var touchN = 0
    private var moveGapN = 0
    private var moveN = 0
    private var historyN = 0
    private var overflow = 0
    private var fullscreenN = 0
    private var floatingN = 0
    private var lastMoveUptimeMs = -1L
    private var refreshSum = 0.0
    private var refreshN = 0

    private val uiFrameUs = LongArray(MAX_FRAMES)
    private var uiFrameN = 0
    private var uiFrameOverflow = 0
    private var lastFrameNs = 0L
    private var frameLoop = false

    private var startedAtMs = 0L
    private var cpuStartMs = 0L
    private var pssStartKb = 0L
    private var thermalStart = -1
    private var currentStartUa = Int.MIN_VALUE
    private var energyStartNwh = Long.MIN_VALUE
    private var nativeStart = LongArray(10)

    private val pageLock = Any()
    private val pageDelivery = ArrayList<Double>(2048)
    private val pageRaf = ArrayList<Double>(2048)
    private val pageEventDuration = ArrayList<Double>(512)
    private var pageMoves = 0
    private var pageCoalesced = 0
    private var pageScrolls = 0
    private var pageLongTasks = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoop) return
            if (measuring && lastFrameNs != 0L) {
                val delta = frameTimeNanos - lastFrameNs
                if (delta in 1..250_000_000L) {
                    if (uiFrameN < uiFrameUs.size) uiFrameUs[uiFrameN++] = delta / 1000L
                    else uiFrameOverflow++
                }
            }
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Called around the exact production Gecko/APZ handler. Main-thread only. */
    fun beginTouch(host: Int, view: View, event: MotionEvent): Int {
        if (!measuring) return -1
        if (touchN >= MAX_TOUCH) { overflow++; return -1 }
        val i = touchN++
        val nowMs = android.os.SystemClock.uptimeMillis()
        eventAgeUs[i] = max(0L, nowMs - event.eventTime) * 1000L
        geckoStartNs[i] = android.os.SystemClock.elapsedRealtimeNanos()
        historyN += event.historySize
        if (host == HOST_FULLSCREEN) fullscreenN++ else if (host == HOST_FLOATING) floatingN++
        view.display?.refreshRate?.takeIf { it > 0f }?.let { refreshSum += it; refreshN++ }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            moveN++
            if (lastMoveUptimeMs >= 0 && moveGapN < moveGapUs.size) {
                moveGapUs[moveGapN++] = max(0L, event.eventTime - lastMoveUptimeMs) * 1000L
            }
            lastMoveUptimeMs = event.eventTime
        }
        return i
    }

    fun endTouch(token: Int) {
        if (token < 0 || token >= touchN) return
        geckoCallUs[token] = max(0L, android.os.SystemClock.elapsedRealtimeNanos() - geckoStartNs[token]) / 1000L
    }

    /** Loopback benchmark page sends already-aggregated browser-domain samples here. */
    fun addPageTelemetry(body: String) {
        if (!measuring || body.length > 128_000) return
        runCatching {
            val o = JSONObject(body)
            synchronized(pageLock) {
                appendJsonNumbers(o.optJSONArray("delivery"), pageDelivery, 4096)
                appendJsonNumbers(o.optJSONArray("raf"), pageRaf, 4096)
                appendJsonNumbers(o.optJSONArray("eventDuration"), pageEventDuration, 2048)
                pageMoves += o.optInt("moves", 0).coerceAtLeast(0)
                pageCoalesced += o.optInt("coalesced", 0).coerceAtLeast(0)
                pageScrolls += o.optInt("scrolls", 0).coerceAtLeast(0)
                pageLongTasks += o.optInt("longTasks", 0).coerceAtLeast(0)
            }
        }
    }

    private fun appendJsonNumbers(array: JSONArray?, out: MutableList<Double>, cap: Int) {
        if (array == null) return
        var i = 0
        while (i < array.length() && out.size < cap) {
            val v = array.optDouble(i, Double.NaN)
            if (v.isFinite() && v >= 0.0 && v < 1000.0) out.add(v)
            i++
        }
    }

    fun startPairedSuite(context: Context, host: Int, pairs: Int = DEFAULT_PAIRS, durationMs: Long = DEFAULT_BLOCK_MS) {
        main.post {
            stopInternal(export = false)
            app = context.applicationContext
            expectedHost = host
            blockMs = durationMs.coerceIn(5_000L, 60_000L)
            val count = pairs.coerceIn(6, 16)
            runId = utcStamp()
            results.clear()
            specs = buildList {
                repeat(count) { pair ->
                    // AB / BA / BA / AB is balanced against linear drift while every
                    // pair still contains both methods back-to-back.
                    val aFirst = pair % 4 == 0 || pair % 4 == 3
                    val first = if (aFirst) PageTouchDispatch.Arm.BUFFERED_139 else PageTouchDispatch.Arm.UNBUFFERED_140
                    val second = if (aFirst) PageTouchDispatch.Arm.UNBUFFERED_140 else PageTouchDispatch.Arm.BUFFERED_139
                    add(Spec(pair, 0, first)); add(Spec(pair, 1, second))
                }
            }
            specIndex = 0; running = true; frameLoop = true; lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
            toast("A/B ready. Use the real page normally. First measured block starts in 6 seconds.")
            main.postDelayed({ scheduleSpec() }, 6_000L)
        }
    }

    fun stop(context: Context? = null) {
        main.post {
            if (context != null) app = context.applicationContext
            stopInternal(export = true)
        }
    }

    fun setManualArm(arm: PageTouchDispatch.Arm) {
        if (running) return
        PageTouchDispatch.arm = arm
    }

    fun lastSummary(context: Context): String =
        context.getSharedPreferences("input-ab-benchmark", Context.MODE_PRIVATE)
            .getString("last-summary", "No completed A/B run yet.") ?: "No completed A/B run yet."

    private fun scheduleSpec() {
        if (!running) return
        if (specIndex >= specs.size) { finishSuite(); return }
        val spec = specs[specIndex]
        PageTouchDispatch.arm = spec.arm
        measuring = false
        toast("Pair ${spec.pair + 1}/${specs.size / 2} · ${spec.arm.shortLabel} · settle ${SETTLE_MS / 1000.0}s, then interact continuously")
        main.postDelayed({ startBlock(spec) }, SETTLE_MS)
    }

    private fun startBlock(spec: Spec) {
        if (!running || specs.getOrNull(specIndex) != spec) return
        resetSamples()
        blockId++
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        cpuStartMs = Process.getElapsedCpuTime()
        pssStartKb = Debug.getPss()
        thermalStart = thermal()
        currentStartUa = batteryInt(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        energyStartNwh = batteryLong(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        nativeStart = nativeStats()
        measuring = true
        lastFrameNs = 0L
        toast("MEASURE ${spec.arm.shortLabel} — keep scrolling/dragging/tapping; no confirmation tap needed")
        main.postDelayed({ finishBlock(spec) }, blockMs)
    }

    private fun finishBlock(spec: Spec) {
        if (!running || specs.getOrNull(specIndex) != spec) return
        measuring = false
        val elapsed = max(1L, android.os.SystemClock.elapsedRealtime() - startedAtMs)
        val nativeEnd = nativeStats()
        val thermalEnd = thermal()
        val endEnergy = batteryLong(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val page = takePageSnapshot()
        val dominant = if (floatingN > fullscreenN) HOST_FLOATING else HOST_FULLSCREEN
        val dominantN = max(floatingN, fullscreenN)
        val hostShare = if (touchN == 0) 0.0 else dominantN.toDouble() / touchN
        val thermalMatched = thermalStart < 0 || thermalEnd < 0 || abs(thermalEnd - thermalStart) <= 1
        val enoughInput = touchN >= 50 && moveN >= 20
        val hostOkay = expectedHost == HOST_ANY || (dominant == expectedHost && hostShare >= .90)
        val valid = enoughInput && hostOkay && thermalMatched && overflow == 0
        val quality = buildList {
            if (!enoughInput) add("too_few_page_events")
            if (!hostOkay) add("window_mode_mismatch")
            if (!thermalMatched) add("thermal_status_changed")
            if (overflow != 0) add("touch_ring_overflow")
            if (uiFrameOverflow != 0) add("ui_frame_ring_overflow")
            if (isEmpty()) add("ok")
        }.joinToString("+")
        val hz = if (refreshN > 0) refreshSum / refreshN else 0.0
        val budgetUs = if (hz > 1.0) 1_000_000.0 / hz else 8_333.333
        var misses = 0
        repeat(uiFrameN) { if (uiFrameUs[it] > budgetUs * 1.15) misses++ }
        val cpuPct = (Process.getElapsedCpuTime() - cpuStartMs).toDouble() / elapsed * 100.0
        val energyDelta = if (energyStartNwh != Long.MIN_VALUE && endEnergy != Long.MIN_VALUE) endEnergy - energyStartNwh else Long.MIN_VALUE
        val result = BlockResult(
            runId, spec.pair, spec.order, spec.arm, expectedHost, dominant, elapsed,
            touchN, moveN, historyN, overflow,
            percentileUs(eventAgeUs, touchN, .50), percentileUs(eventAgeUs, touchN, .95),
            percentileUs(geckoCallUs, touchN, .50), percentileUs(geckoCallUs, touchN, .95),
            percentileUs(moveGapUs, moveGapN, .50), percentileUs(moveGapUs, moveGapN, .95),
            percentileUs(uiFrameUs, uiFrameN, .50), percentileUs(uiFrameUs, uiFrameN, .95),
            if (uiFrameN == 0) Double.NaN else misses * 100.0 / uiFrameN, hz, cpuPct,
            Debug.getPss() - pssStartKb, thermalStart, thermalEnd,
            if (currentStartUa == Int.MIN_VALUE) 0 else currentStartUa,
            energyDelta,
            nativeEnd.getOrElse(2) { 0 } - nativeStart.getOrElse(2) { 0 },
            nativeEnd.getOrElse(3) { 0 } - nativeStart.getOrElse(3) { 0 },
            nativeEnd.getOrElse(6) { 0 } - nativeStart.getOrElse(6) { 0 },
            percentile(page.delivery, .50), percentile(page.delivery, .95),
            percentile(page.raf, .95), percentile(page.eventDuration, .95),
            page.moves, page.coalesced, page.scrolls, page.longTasks,
            valid, quality
        )
        results.add(result)
        specIndex++
        toast("Block ${specIndex}/${specs.size} saved · ${if (valid) "valid" else quality}")
        main.postDelayed({ scheduleSpec() }, BETWEEN_MS)
    }

    private fun resetSamples() {
        touchN = 0; moveGapN = 0; moveN = 0; historyN = 0; overflow = 0
        fullscreenN = 0; floatingN = 0; lastMoveUptimeMs = -1L
        refreshSum = 0.0; refreshN = 0
        uiFrameN = 0; uiFrameOverflow = 0; lastFrameNs = 0L
        synchronized(pageLock) {
            pageDelivery.clear(); pageRaf.clear(); pageEventDuration.clear()
            pageMoves = 0; pageCoalesced = 0; pageScrolls = 0; pageLongTasks = 0
        }
    }

    private fun takePageSnapshot(): PageSnapshot = synchronized(pageLock) {
        PageSnapshot(pageDelivery.toDoubleArray(), pageRaf.toDoubleArray(), pageEventDuration.toDoubleArray(),
            pageMoves, pageCoalesced, pageScrolls, pageLongTasks)
    }

    private fun finishSuite() {
        if (!running) return
        running = false; measuring = false; frameLoop = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        val context = app ?: return
        val summary = analyze(results)
        val files = export(context, summary, results)
        context.getSharedPreferences("input-ab-benchmark", Context.MODE_PRIVATE).edit()
            .putString("last-summary", summary + "\n\nExport: " + files.joinToString()).apply()
        notifyDone(context, summary)
        toast("A/B complete. ${summary.lineSequence().firstOrNull().orEmpty()}")
    }

    private fun stopInternal(export: Boolean) {
        val wasRunning = running
        running = false; measuring = false; frameLoop = false
        main.removeCallbacksAndMessages(null)
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        if (export && wasRunning && results.isNotEmpty()) {
            val context = app ?: return
            val summary = "STOPPED EARLY\n" + analyze(results)
            val files = export(context, summary, results)
            context.getSharedPreferences("input-ab-benchmark", Context.MODE_PRIVATE).edit()
                .putString("last-summary", summary + "\n\nExport: " + files.joinToString()).apply()
        }
    }

    private data class Metric(val name: String, val margin: Double, val getter: (BlockResult) -> Double, val lowerBetter: Boolean = true)
    private data class Finding(val name: String, val delta: Double, val low: Double, val high: Double, val margin: Double, val verdict: String, val pairs: Int)

    private fun analyze(blocks: List<BlockResult>): String {
        val byPair = blocks.groupBy { it.pair }
        val paired = ArrayList<Pair<BlockResult, BlockResult>>()
        for ((_, group) in byPair) {
            val a = group.firstOrNull { it.arm == PageTouchDispatch.Arm.BUFFERED_139 }
            val b = group.firstOrNull { it.arm == PageTouchDispatch.Arm.UNBUFFERED_140 }
            if (a != null && b != null && a.valid && b.valid && a.dominantHost == b.dominantHost) paired.add(a to b)
        }
        if (paired.size < 6) {
            return "NO DECISION — only ${paired.size} valid paired blocks (need at least 6).\n" +
                "Completed blocks=${blocks.size}, valid=${blocks.count { it.valid }}. Missing data is not a tie."
        }
        val controlled = paired.count { it.first.pageDeliveryP95Ms != null && it.second.pageDeliveryP95Ms != null } >= 6
        val metrics = buildList {
            add(Metric("Android event age p95", .75, { it.eventAgeP95Ms }))
            add(Metric("Gecko-call wall p95", .25, { it.geckoCallP95Ms }))
            add(Metric("UI-thread frame p95", .50, { it.uiFrameP95Ms }))
            add(Metric("UI deadline-miss %", 1.5, { it.uiDeadlineMissPct }))
            add(Metric("process CPU %", 3.0, { it.processCpuPct }))
            if (controlled) add(Metric("DOM pointer delivery p95", 1.0, { it.pageDeliveryP95Ms ?: Double.NaN }))
            if (controlled) add(Metric("controlled-page rAF p95", .50, { it.pageRafP95Ms ?: Double.NaN }))
        }
        val findings = metrics.mapNotNull { metric -> compareMetric(paired, metric) }
        val primaryNames = if (controlled) setOf("Android event age p95", "DOM pointer delivery p95", "UI deadline-miss %")
            else setOf("Android event age p95", "UI deadline-miss %")
        val primary = findings.filter { it.name in primaryNames }
        val bWins = primary.count { it.verdict == "140 WIN" }
        val aWins = primary.count { it.verdict == "139 WIN" }
        val overall = when {
            bWins > 0 && aWins == 0 -> "WINNER: 140 unbuffered"
            aWins > 0 && bWins == 0 -> "WINNER: 139 buffered"
            aWins == 0 && bWins == 0 && primary.all { it.verdict == "EQUIVALENT" } -> "RESULT: practically equivalent on primary metrics"
            else -> "RESULT: tradeoff / no single dominant method"
        }
        return buildString {
            append(overall).append("\n")
            append("Statistical unit: ").append(paired.size).append(" counterbalanced paired blocks; 95% bootstrap CI over pair differences.\n")
            append("Delta convention: 140 minus 139. Negative is better for latency/jank/CPU.\n")
            for (f in findings) {
                append(String.format(Locale.US, "%s: %s · Δ %.3f [%.3f, %.3f], practical margin ±%.3f, n=%d\n",
                    f.name, f.verdict, f.delta, f.low, f.high, f.margin, f.pairs))
            }
            val a = paired.map { it.first }
            val b = paired.map { it.second }
            append(String.format(Locale.US,
                "139 medians: eventAgeP95=%.2fms, frameP95=%.2fms, CPU=%.1f%%, history/move=%.2f\n",
                median(a.map { it.eventAgeP95Ms }), median(a.map { it.uiFrameP95Ms }), median(a.map { it.processCpuPct }),
                median(a.map { if (it.moveCount == 0) 0.0 else it.historySamples.toDouble() / it.moveCount })))
            append(String.format(Locale.US,
                "140 medians: eventAgeP95=%.2fms, frameP95=%.2fms, CPU=%.1f%%, history/move=%.2f\n",
                median(b.map { it.eventAgeP95Ms }), median(b.map { it.uiFrameP95Ms }), median(b.map { it.processCpuPct }),
                median(b.map { if (it.moveCount == 0) 0.0 else it.historySamples.toDouble() / it.moveCount })))
            append("Frame cadence is Bubble UI-thread Choreographer timing, not child-surface presentation. Native submit/release counts are not physical scanout timestamps.")
        }.trim()
    }

    private fun compareMetric(pairs: List<Pair<BlockResult, BlockResult>>, m: Metric): Finding? {
        val diffs = pairs.mapNotNull { (a, b) ->
            val av = m.getter(a); val bv = m.getter(b)
            if (!av.isFinite() || !bv.isFinite()) null else (bv - av) * if (m.lowerBetter) 1.0 else -1.0
        }
        if (diffs.size < 6) return null
        val mean = diffs.average()
        val (low, high) = bootstrapMeanCi(diffs, 5000, 139140L + m.name.hashCode())
        val verdict = when {
            high < -m.margin -> "140 WIN"
            low > m.margin -> "139 WIN"
            low >= -m.margin && high <= m.margin -> "EQUIVALENT"
            else -> "INCONCLUSIVE"
        }
        return Finding(m.name, mean, low, high, m.margin, verdict, diffs.size)
    }

    private fun bootstrapMeanCi(values: List<Double>, reps: Int, seed: Long): Pair<Double, Double> {
        val random = Random(seed.toInt())
        val out = DoubleArray(reps)
        repeat(reps) { r ->
            var sum = 0.0
            repeat(values.size) { sum += values[random.nextInt(values.size)] }
            out[r] = sum / values.size
        }
        out.sort()
        return out[(reps * .025).toInt().coerceIn(0, reps - 1)] to out[(reps * .975).toInt().coerceIn(0, reps - 1)]
    }

    private fun percentileUs(data: LongArray, n: Int, p: Double): Double {
        if (n <= 0) return Double.NaN
        val copy = data.copyOf(n.coerceAtMost(data.size)); copy.sort()
        val i = ((copy.size - 1) * p).roundToLong().toInt().coerceIn(0, copy.lastIndex)
        return copy[i] / 1000.0
    }
    private fun percentile(data: DoubleArray, p: Double): Double? {
        if (data.isEmpty()) return null
        val copy = data.copyOf(); copy.sort()
        val i = ((copy.size - 1) * p).roundToLong().toInt().coerceIn(0, copy.lastIndex)
        return copy[i]
    }
    private fun median(v: List<Double>): Double {
        val x = v.filter { it.isFinite() }.sorted(); if (x.isEmpty()) return Double.NaN
        return if (x.size % 2 == 1) x[x.size / 2] else (x[x.size / 2 - 1] + x[x.size / 2]) / 2.0
    }

    private fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29) {
        (app?.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1)
    } else -1
    private fun batteryInt(property: Int): Int = app?.getSystemService(BatteryManager::class.java)?.getIntProperty(property) ?: Int.MIN_VALUE
    private fun batteryLong(property: Int): Long = app?.getSystemService(BatteryManager::class.java)?.getLongProperty(property) ?: Long.MIN_VALUE
    private fun nativeStats(): LongArray = runCatching { NativeAhbBridge.nativeDebugStats() }.getOrElse { LongArray(10) }

    @SuppressLint("NewApi")
    private fun export(context: Context, summary: String, blocks: List<BlockResult>): List<String> {
        val csv = buildCsv(blocks)
        val txt = buildString {
            append("Bubble 139 vs 140 production-window A/B\nRun: ").append(runId).append("\n\n")
            append(summary).append("\n\nRaw block rows are in the companion CSV.\n")
            append("139 = normal Android buffered touch delivery. 140 = requestUnbufferedDispatch(MotionEvent) on gesture DOWN.\n")
            append("No positive confirmation tap occurs during measured blocks. Black/broken trials should be stopped and rerun; missing measurements are never encoded as zero.\n")
        }
        val out = ArrayList<String>()
        val base = "Bubble-AB-$runId"
        if (Build.VERSION.SDK_INT >= 29) {
            fun put(name: String, type: String, text: String) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Bubble Benchmarks")
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                    out.add(name)
                }
            }
            put("$base-summary.txt", "text/plain", txt)
            put("$base-blocks.csv", "text/csv", csv)
        } else {
            val dir = File(context.getExternalFilesDir(null), "Bubble Benchmarks").apply { mkdirs() }
            File(dir, "$base-summary.txt").writeText(txt); File(dir, "$base-blocks.csv").writeText(csv)
            out.add(File(dir, "$base-summary.txt").absolutePath); out.add(File(dir, "$base-blocks.csv").absolutePath)
        }
        return out
    }

    private fun buildCsv(blocks: List<BlockResult>): String = buildString {
        append("run,pair,order,arm,expectedHost,dominantHost,durationMs,valid,quality,touches,moves,history,overflow,eventAgeP50Ms,eventAgeP95Ms,geckoCallP50Ms,geckoCallP95Ms,moveGapP50Ms,moveGapP95Ms,uiFrameP50Ms,uiFrameP95Ms,uiDeadlineMissPct,displayHz,processCpuPct,pssDeltaKb,thermalStart,thermalEnd,batteryCurrentUa,energyDeltaNwh,nativeSubmitted,nativeReleased,nativeErrors,pageDeliveryP50Ms,pageDeliveryP95Ms,pageRafP95Ms,pageEventDurationP95Ms,pageMoves,pageCoalesced,pageScrollEvents,pageLongTasks\n")
        blocks.forEach { b ->
            fun d(v: Double?) = if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.4f", v)
            append(listOf(b.runId,b.pair,b.order,b.arm.name,b.expectedHost,b.dominantHost,b.durationMs,b.valid,b.quality,b.touchCount,b.moveCount,b.historySamples,b.overflow,
                d(b.eventAgeP50Ms),d(b.eventAgeP95Ms),d(b.geckoCallP50Ms),d(b.geckoCallP95Ms),d(b.moveGapP50Ms),d(b.moveGapP95Ms),d(b.uiFrameP50Ms),d(b.uiFrameP95Ms),d(b.uiDeadlineMissPct),d(b.displayHz),d(b.processCpuPct),b.pssDeltaKb,b.thermalStart,b.thermalEnd,b.batteryCurrentUa,b.energyDeltaNwh,b.nativeSubmitted,b.nativeReleased,b.nativeErrors,d(b.pageDeliveryP50Ms),d(b.pageDeliveryP95Ms),d(b.pageRafP95Ms),d(b.pageEventDurationP95Ms),b.pageMoves,b.pageCoalesced,b.pageScrollEvents,b.pageLongTasks).joinToString(","))
            append('\n')
        }
    }

    private fun notifyDone(context: Context, summary: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("bubble_ab", "Bubble A/B benchmark", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, InputBenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 141, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val first = summary.lineSequence().firstOrNull().orEmpty().take(120)
        val notification = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, "bubble_ab") else android.app.Notification.Builder(context)
        nm.notify(141, notification.setSmallIcon(R.drawable.ic_notification).setContentTitle("Bubble A/B complete")
            .setContentText(first).setStyle(android.app.Notification.BigTextStyle().bigText(summary.take(4000))).setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun toast(text: String) { app?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
    private fun utcStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
