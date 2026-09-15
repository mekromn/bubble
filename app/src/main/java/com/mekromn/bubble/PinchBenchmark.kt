package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Physical two-finger pinch/zoom A/B for Bubble's real floating Gecko page.
 *
 * Unlike the scrolling benchmark, blocks are normalized around complete two-finger gestures and the
 * actual finger-span trajectory is recorded. A dormant content-script bridge samples VisualViewport
 * only while a run is armed, with no page touch listeners. A ping/ack clock sync maps its rAF clock
 * into Android's monotonic time domain, letting us compare input span against the zoom state Gecko
 * exposes to the page without pretending that this is photon timing.
 */
@SuppressLint("NewApi")
internal object PinchBenchmark {
    private const val TARGET_GESTURES = 5
    private const val MIN_BLOCK_NS = 5_000_000_000L
    private const val HARD_BLOCK_MS = 28_000L
    private const val READY_TIMEOUT_MS = 20_000L
    private const val READY_SETTLE_MS = 500L
    private const val BETWEEN_BLOCK_MS = 1800L
    private const val MAX_INPUT_SAMPLES = 12000
    private const val MAX_VIEWPORT_SAMPLES = 6000

    private val main = Handler(Looper.getMainLooper())
    private var run: RunState? = null
    private var pending: PendingBlock? = null
    private var active: Recorder? = null

    data class ViewportSample(
        val jsMs: Double,
        val scale: Double,
        val pageLeft: Double,
        val pageTop: Double,
        val width: Double,
        val height: Double,
        val dpr: Double
    )

    data class Status(
        val running: Boolean,
        val block: Int,
        val totalBlocks: Int,
        val phase: String,
        val latestSummary: String
    )

    private data class SpanSample(
        val timeNs: Long,
        val gesture: Int,
        val logRatio: Double,
        val spanPx: Double,
        val cxPx: Double,
        val cyPx: Double
    )

    private data class PendingBlock(
        val index: Int,
        val pair: Int,
        val transport: RendererArena.Transport,
        val recorder: Recorder,
        var routeReady: Boolean = false,
        var ready: Boolean = false,
        var readyStartedMs: Long = 0L
    )

    private data class RunState(
        val app: Context,
        val pairs: Int,
        val seed: Long,
        val order: List<RendererArena.Transport>,
        val results: MutableList<BlockResult> = ArrayList()
    )

    private data class StartSnapshot(
        val processCpuMs: Long,
        val mainCpuNs: Long,
        val rx: Long,
        val tx: Long,
        val relay: LongArray
    )

    private data class EndSnapshot(
        val processCpuMs: Long,
        val mainCpuNs: Long,
        val rx: Long,
        val tx: Long,
        val pssKb: Long,
        val heapUsedBytes: Long,
        val relay: LongArray
    )

    private class Histogram(private val widthNs: Long, private val bins: Int = 1024) {
        private val values = LongArray(bins)
        var count = 0L
            private set
        fun add(valueNs: Long) {
            val v = valueNs.coerceAtLeast(0L)
            val i = min((v / widthNs).toInt(), bins - 1)
            values[i]++
            count++
        }
        fun percentile(q: Double): Double {
            if (count == 0L) return Double.NaN
            val target = max(1L, ceil(count * q.coerceIn(0.0, 1.0)).toLong())
            var total = 0L
            for (i in values.indices) {
                total += values[i]
                if (total >= target) return (i + .5) * widthNs / 1_000_000.0
            }
            return (values.size - .5) * widthNs / 1_000_000.0
        }
    }

    private class Recorder(
        val index: Int,
        val pair: Int,
        val transport: RendererArena.Transport,
        val session: GeckoSession,
        val runId: String
    ) {
        val eventAge = Histogram(125_000L)
        val preamble = Histogram(25_000L)
        val sourceSpacing = Histogram(125_000L)
        val input = ArrayList<SpanSample>()
        val viewport = ArrayList<ViewportSample>()
        var startedNs = 0L
        var endedNs = 0L
        var clockOffsetNs = Double.NaN
        var bestSyncRttNs = Long.MAX_VALUE
        var syncCount = 0
        var gestureSerial = 0
        var gesturesCompleted = 0
        var inPinch = false
        var gestureStartSpan = 0.0
        var lastSpan = 0.0
        var lastCx = 0.0
        var lastCy = 0.0
        var lastSourceNs = 0L
        var spanTravelPx = 0.0
        var centroidTravelPx = 0.0
        var minSpanPx = Double.POSITIVE_INFINITY
        var maxSpanPx = 0.0
        var reversalCount = 0
        var lastSpanDirection = 0
        var eventCount = 0L
        var moveCount = 0L
        var historySamples = 0L
        var extraPointerEvents = 0L
        var finishRequested = false
        var forcedFinish = false
        lateinit var start: StartSnapshot
        var hardFinish: Runnable? = null

        fun acceptSync(t0Ns: Long, t1Ns: Long, jsMs: Double) {
            val rtt = (t1Ns - t0Ns).coerceAtLeast(0L)
            syncCount++
            if (rtt < bestSyncRttNs) {
                bestSyncRttNs = rtt
                clockOffsetNs = (t0Ns + rtt / 2.0) - jsMs * 1_000_000.0
            }
        }

        fun addViewportSamples(samples: List<ViewportSample>) {
            if (viewport.size >= MAX_VIEWPORT_SAMPLES) return
            val room = MAX_VIEWPORT_SAMPLES - viewport.size
            viewport.addAll(samples.take(room))
        }

        fun begin(app: Context) {
            startedNs = System.nanoTime()
            start = StartSnapshot(
                Process.getElapsedCpuTime(), Debug.threadCpuTimeNanos(),
                safeUidRx(), safeUidTx(), relayStats()
            )
            hardFinish = Runnable {
                if (active === this) {
                    forcedFinish = true
                    if (inPinch) finishRequested = true else finishAsync(this)
                }
            }.also { main.postDelayed(it, HARD_BLOCK_MS) }
        }

        fun record(event: MotionEvent, receiptNs: Long) {
            eventCount++
            eventAge.add(receiptNs - event.eventTimeNanos)
            if (event.pointerCount > 2) extraPointerEvents++
            if (event.actionMasked == MotionEvent.ACTION_MOVE) moveCount++

            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
                if (!inPinch) {
                    gestureSerial++
                    gestureStartSpan = span(event, -1)
                    lastSpan = gestureStartSpan
                    lastCx = centroidX(event, -1)
                    lastCy = centroidY(event, -1)
                    lastSpanDirection = 0
                    inPinch = gestureStartSpan > 0.0
                }
            }

            if (event.pointerCount >= 2 && inPinch) {
                for (h in 0 until event.historySize) {
                    historySamples++
                    recordSpan(event.getHistoricalEventTimeNanos(h),
                        span(event, h), centroidX(event, h), centroidY(event, h))
                }
                recordSpan(event.eventTimeNanos, span(event, -1), centroidX(event, -1), centroidY(event, -1))
            }

            val pointersAfter = when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> event.pointerCount - 1
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
                else -> event.pointerCount
            }
            if (inPinch && pointersAfter < 2) {
                inPinch = false
                gesturesCompleted++
                gestureStartSpan = 0.0
                lastSpanDirection = 0
            }
        }

        private fun recordSpan(timeNs: Long, span: Double, cx: Double, cy: Double) {
            if (span <= 0.0 || !span.isFinite() || input.size >= MAX_INPUT_SAMPLES) return
            if (lastSourceNs != 0L && timeNs > lastSourceNs) sourceSpacing.add(timeNs - lastSourceNs)
            if (timeNs <= lastSourceNs) return
            lastSourceNs = timeNs
            minSpanPx = min(minSpanPx, span)
            maxSpanPx = max(maxSpanPx, span)
            if (lastSpan > 0.0) {
                val d = span - lastSpan
                spanTravelPx += abs(d)
                val direction = when { d > .25 -> 1; d < -.25 -> -1; else -> 0 }
                if (direction != 0 && lastSpanDirection != 0 && direction != lastSpanDirection) reversalCount++
                if (direction != 0) lastSpanDirection = direction
                centroidTravelPx += hypot(cx - lastCx, cy - lastCy)
            }
            lastSpan = span
            lastCx = cx
            lastCy = cy
            val base = gestureStartSpan.takeIf { it > 0.0 } ?: span
            input += SpanSample(timeNs, gestureSerial, ln(span / base), span, cx, cy)
        }

        private fun span(event: MotionEvent, history: Int): Double {
            val x0 = if (history >= 0) event.getHistoricalX(0, history) else event.getX(0)
            val y0 = if (history >= 0) event.getHistoricalY(0, history) else event.getY(0)
            val x1 = if (history >= 0) event.getHistoricalX(1, history) else event.getX(1)
            val y1 = if (history >= 0) event.getHistoricalY(1, history) else event.getY(1)
            return hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        }
        private fun centroidX(event: MotionEvent, history: Int): Double {
            val a = if (history >= 0) event.getHistoricalX(0, history) else event.getX(0)
            val b = if (history >= 0) event.getHistoricalX(1, history) else event.getX(1)
            return (a + b) / 2.0
        }
        private fun centroidY(event: MotionEvent, history: Int): Double {
            val a = if (history >= 0) event.getHistoricalY(0, history) else event.getY(0)
            val b = if (history >= 0) event.getHistoricalY(1, history) else event.getY(1)
            return (a + b) / 2.0
        }
    }

    data class BlockResult(
        val index: Int,
        val pair: Int,
        val transport: RendererArena.Transport,
        val durationMs: Double,
        val gestures: Int,
        val events: Long,
        val moves: Long,
        val historySamples: Long,
        val extraPointerEvents: Long,
        val eventAgeP95Ms: Double,
        val preambleP95Ms: Double,
        val sourceSpacingP95Ms: Double,
        val spanTravelPx: Double,
        val centroidTravelPx: Double,
        val spanRange: Double,
        val reversals: Int,
        val viewportSamples: Int,
        val viewportFrameP95Ms: Double,
        val trackingLagMs: Double,
        val trackingErrorPct: Double,
        val clockSyncRttMs: Double,
        val appCpuMsPerSec: Double,
        val mainCpuMsPerSec: Double,
        val pssKb: Long,
        val heapUsedKb: Long,
        val rxBytes: Long,
        val txBytes: Long,
        val relaySubmitted: Long,
        val relayReleased: Long,
        val relayErrors: Long,
        val routeValid: Boolean,
        val forcedFinish: Boolean
    )

    data class MetricDecision(
        val name: String,
        val unit: String,
        val medianDirectMinusRelay: Double,
        val ciLow: Double,
        val ciHigh: Double,
        val threshold: Double,
        val verdict: String
    )

    fun status(context: Context): Status {
        val latest = context.getSharedPreferences("pinch-benchmark", Context.MODE_PRIVATE)
            .getString("latest-summary", "No completed pinch benchmark yet.").orEmpty()
        val state = run
        val p = pending
        val a = active
        return when {
            state == null -> Status(false, 0, 0, "idle", latest)
            a != null -> Status(true, a.index + 1, state.order.size,
                "MEASURING · ${a.gesturesCompleted}/$TARGET_GESTURES completed pinches", latest)
            p != null -> Status(true, p.index + 1, state.order.size,
                if (p.ready) "ready · begin a two-finger pinch" else "preparing hidden renderer + viewport clock", latest)
            else -> Status(true, state.results.size + 1, state.order.size, "switching / analyzing", latest)
        }
    }

    fun start(context: Context, pairs: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (run != null || RendererBenchmark.status(context).running) return false
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The hardcore pinch benchmark requires Android 16.", Toast.LENGTH_LONG).show()
            return false
        }
        val window = BubbleService.active?.window
        val session = Workspace.peek()?.selected?.session
        if (window == null || window.mode != FloatingMode.CHAT || session == null || !session.isOpen ||
            Workspace.peek()?.selected?.url?.let(Policy::isWeb) != true) {
            Toast.makeText(context, "Open a live http/https page in Bubble's floating chat window first.", Toast.LENGTH_LONG).show()
            return false
        }
        val count = pairs.coerceIn(2, 10)
        val seed = SystemClock.elapsedRealtimeNanos() xor BuildFingerprint.seed()
        val order = ArrayList<RendererArena.Transport>(count * 2)
        val firstDirect = (seed and 1L) == 0L
        repeat(count) { pair ->
            val directFirst = if (pair % 2 == 0) firstDirect else !firstDirect
            if (directFirst) {
                order += RendererArena.Transport.DIRECT_GECKO_SURFACE
                order += RendererArena.Transport.RELAY_LATEST_BP
            } else {
                order += RendererArena.Transport.RELAY_LATEST_BP
                order += RendererArena.Transport.DIRECT_GECKO_SURFACE
            }
        }
        val app = context.applicationContext
        run = RunState(app, count, seed, order)
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        scheduleBlock(0)
        return true
    }

    fun cancel(message: String = "Pinch benchmark canceled.") {
        val state = run ?: return
        active?.hardFinish?.let(main::removeCallbacks)
        val session = active?.session ?: pending?.recorder?.session
        val runId = active?.runId ?: pending?.recorder?.runId
        if (session != null && runId != null) PinchBridge.disarm(session, runId)
        active = null
        pending = null
        run = null
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        RendererArena.transport = RendererArena.Transport.DIRECT_GECKO_SURFACE
        BubbleService.active?.window?.setRendererTransportForArena(RendererArena.Transport.DIRECT_GECKO_SURFACE)
        Toast.makeText(state.app, message, Toast.LENGTH_LONG).show()
    }

    /** Called around the same pre-Gecko PageTouchDispatch section for both renderer arms. */
    fun beforePage(view: View, event: MotionEvent): Long {
        var recorder = active
        if (recorder == null) {
            val p = pending ?: return 0L
            if (!p.ready || event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount < 2 ||
                !matchesActiveInput(view, p.transport)) return 0L
            recorder = p.recorder
            pending = null
            active = recorder
            recorder.begin(run?.app ?: return 0L)
        }
        if (!matchesActiveInput(view, recorder.transport)) return 0L
        val receipt = System.nanoTime()
        recorder.record(event, receipt)
        return receipt
    }

    fun afterPage(tokenNs: Long, event: MotionEvent) {
        if (tokenNs == 0L) return
        val recorder = active ?: return
        recorder.preamble.add(System.nanoTime() - tokenNs)
        if (!recorder.inPinch && recorder.gesturesCompleted >= TARGET_GESTURES &&
            System.nanoTime() - recorder.startedNs >= MIN_BLOCK_NS) finishAsync(recorder)
        else if (!recorder.inPinch && recorder.finishRequested) finishAsync(recorder)
    }

    fun onBridgeReady(session: GeckoSession) {
        val p = pending ?: return
        if (p.recorder.session === session) main.post { prepareBridgeIfReady(p) }
    }

    fun onBridgeDisconnected(session: GeckoSession) {
        val a = active
        if (a != null && a.session === session) cancel("Pinch viewport bridge disconnected; no partial result was trusted.")
    }

    fun onClockSync(session: GeckoSession, runId: String, seq: Int, t0Ns: Long, jsMs: Double) {
        val recorder = when {
            active?.session === session && active?.runId == runId -> active
            pending?.recorder?.session === session && pending?.recorder?.runId == runId -> pending?.recorder
            else -> null
        } ?: return
        if (seq !in 0..15) return
        val now = System.nanoTime()
        if (t0Ns <= 0L || now - t0Ns !in 0L..2_000_000_000L) return
        recorder.acceptSync(t0Ns, now, jsMs)
    }

    fun onViewportSamples(session: GeckoSession, runId: String, samples: List<ViewportSample>) {
        val recorder = when {
            active?.session === session && active?.runId == runId -> active
            pending?.recorder?.session === session && pending?.recorder?.runId == runId -> pending?.recorder
            else -> null
        } ?: return
        recorder.addViewportSamples(samples)
    }

    private fun scheduleBlock(index: Int) {
        val state = run ?: return
        val transport = state.order[index]
        val session = Workspace.peek()?.selected?.session
        if (session == null || !session.isOpen) {
            cancel("The page session disappeared before the next pinch block.")
            return
        }
        val recorder = Recorder(index, index / 2, transport, session,
            "${state.seed.toString(16)}-${index}-${SystemClock.elapsedRealtimeNanos().toString(16)}")
        val p = PendingBlock(index, index / 2, transport, recorder, readyStartedMs = SystemClock.uptimeMillis())
        pending = p
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        RendererArena.transport = transport
        BubbleService.active?.window?.setRendererTransportForArena(transport)
        waitForRoute(p)
    }

    private fun waitForRoute(p: PendingBlock) {
        if (pending !== p || run == null) return
        val window = BubbleService.active?.window
        val host = window?.pageHost
        val session = Workspace.peek()?.selected?.session
        val good = window?.mode == FloatingMode.CHAT && host?.transport == p.transport &&
            host.pageView.isAttachedToWindow && host.pageView.hasWindowFocus() &&
            session === p.recorder.session && session?.isOpen == true
        if (good) {
            p.routeReady = true
            prepareBridgeIfReady(p)
            return
        }
        if (SystemClock.uptimeMillis() - p.readyStartedMs > READY_TIMEOUT_MS) {
            cancel("Pinch benchmark renderer never reached a stable focused floating route.")
        } else main.postDelayed({ waitForRoute(p) }, 60L)
    }

    private fun prepareBridgeIfReady(p: PendingBlock) {
        if (pending !== p || !p.routeReady || p.ready || run == null) return
        if (!PinchBridge.ready(p.recorder.session)) {
            if (SystemClock.uptimeMillis() - p.readyStartedMs > READY_TIMEOUT_MS) {
                cancel("Pinch VisualViewport bridge did not connect to the selected top-level page.")
            } else main.postDelayed({ prepareBridgeIfReady(p) }, 120L)
            return
        }
        if (!PinchBridge.arm(p.recorder.session, p.recorder.runId)) {
            main.postDelayed({ prepareBridgeIfReady(p) }, 120L)
            return
        }
        repeat(6) { seq ->
            main.postDelayed({
                if (pending === p) PinchBridge.sync(p.recorder.session, p.recorder.runId, seq, System.nanoTime())
            }, seq * 38L)
        }
        main.postDelayed({
            if (pending !== p || run == null) return@postDelayed
            if (p.recorder.syncCount < 2 || !p.recorder.clockOffsetNs.isFinite()) {
                cancel("Pinch clock synchronization failed; no fake latency result was produced.")
                return@postDelayed
            }
            p.ready = true
            Toast.makeText(run?.app,
                "Pinch block ${p.index + 1}/${run?.order?.size}: hidden renderer ready. Do $TARGET_GESTURES aggressive two-finger zoom gestures — big in/out reversals, natural speed. Lift both fingers between gestures.",
                Toast.LENGTH_LONG).show()
        }, READY_SETTLE_MS)
    }

    private fun finishAsync(recorder: Recorder) {
        if (active !== recorder) return
        main.post { finishBlock(recorder) }
    }

    private fun finishBlock(recorder: Recorder) {
        if (active !== recorder) return
        recorder.endedNs = System.nanoTime()
        recorder.hardFinish?.let(main::removeCallbacks)
        active = null
        PinchBridge.disarm(recorder.session, recorder.runId)
        val state = run ?: return
        val result = resultFrom(recorder, endSnapshot())
        if (!result.routeValid) {
            cancel("Pinch renderer route proof failed; contaminated data was discarded.")
            return
        }
        state.results += result
        val next = recorder.index + 1
        if (next >= state.order.size) complete(state)
        else {
            Toast.makeText(state.app, "Pinch block ${recorder.index + 1}/${state.order.size} captured. Preparing next hidden renderer…", Toast.LENGTH_SHORT).show()
            main.postDelayed({ if (run === state) scheduleBlock(next) }, BETWEEN_BLOCK_MS)
        }
    }

    private fun resultFrom(r: Recorder, end: EndSnapshot): BlockResult {
        val durationMs = ((r.endedNs - r.startedNs).coerceAtLeast(1L)) / 1_000_000.0
        val seconds = durationMs / 1000.0
        val tracking = tracking(r)
        val viewportP95 = viewportFrameP95(r)
        val relaySubmitted = delta(end.relay, r.start.relay, 0)
        val relayReleased = delta(end.relay, r.start.relay, 1)
        val relayErrors = delta(end.relay, r.start.relay, 5)
        val routeValid = if (r.transport == RendererArena.Transport.DIRECT_GECKO_SURFACE)
            relaySubmitted == 0L else relaySubmitted > 0L && relayErrors == 0L
        val spanRange = if (r.minSpanPx.isFinite() && r.minSpanPx > 0.0) r.maxSpanPx / r.minSpanPx else Double.NaN
        return BlockResult(
            r.index, r.pair, r.transport, durationMs, r.gesturesCompleted,
            r.eventCount, r.moveCount, r.historySamples, r.extraPointerEvents,
            r.eventAge.percentile(.95), r.preamble.percentile(.95), r.sourceSpacing.percentile(.95),
            r.spanTravelPx, r.centroidTravelPx, spanRange, r.reversalCount,
            r.viewport.size, viewportP95, tracking.first, tracking.second,
            if (r.bestSyncRttNs == Long.MAX_VALUE) Double.NaN else r.bestSyncRttNs / 1_000_000.0,
            (end.processCpuMs - r.start.processCpuMs).coerceAtLeast(0L) / seconds,
            (end.mainCpuNs - r.start.mainCpuNs).coerceAtLeast(0L) / 1_000_000.0 / seconds,
            end.pssKb, end.heapUsedBytes / 1024,
            end.rx - r.start.rx, end.tx - r.start.tx,
            relaySubmitted, relayReleased, relayErrors, routeValid, r.forcedFinish
        )
    }

    /** Returns best span->VisualViewport lag in ms and residual log-scale error as percent. */
    private fun tracking(r: Recorder): Pair<Double, Double> {
        if (!r.clockOffsetNs.isFinite() || r.input.size < 12 || r.viewport.size < 12) return Double.NaN to Double.NaN
        val mapped = r.viewport.map { v -> (v.jsMs * 1_000_000.0 + r.clockOffsetNs).toLong() to v }
            .filter { it.first in (r.startedNs - 250_000_000L)..(r.endedNs + 250_000_000L) }
            .sortedBy { it.first }
        if (mapped.size < 12) return Double.NaN to Double.NaN
        val groups = r.input.groupBy { it.gesture }.values.filter { it.size >= 3 }
        if (groups.isEmpty()) return Double.NaN to Double.NaN

        var bestLagMs = Double.NaN
        var bestMse = Double.POSITIVE_INFINITY
        var bestCount = 0
        for (lagMs in 0..80) {
            val lagNs = lagMs * 1_000_000L
            var sum = 0.0
            var count = 0
            for (g in groups) {
                val start = g.first().timeNs
                val end = g.last().timeNs
                val baseline = mapped.lastOrNull { it.first <= start } ?: mapped.firstOrNull { it.first >= start } ?: continue
                val baseScale = baseline.second.scale
                if (baseScale <= 0.0) continue
                for ((t, v) in mapped) {
                    val inputTime = t - lagNs
                    if (inputTime < start || inputTime > end) continue
                    val expected = interpolateLogRatio(g, inputTime) ?: continue
                    val observed = ln(v.scale / baseScale)
                    if (!observed.isFinite()) continue
                    val e = observed - expected
                    sum += e * e
                    count++
                }
            }
            if (count >= 20) {
                val mse = sum / count
                if (mse < bestMse) {
                    bestMse = mse
                    bestLagMs = lagMs.toDouble()
                    bestCount = count
                }
            }
        }
        if (!bestLagMs.isFinite() || bestCount < 20) return Double.NaN to Double.NaN
        val rmseLog = sqrt(bestMse)
        return bestLagMs to ((exp(rmseLog) - 1.0) * 100.0)
    }

    private fun interpolateLogRatio(samples: List<SpanSample>, timeNs: Long): Double? {
        if (samples.isEmpty() || timeNs < samples.first().timeNs || timeNs > samples.last().timeNs) return null
        var lo = 0
        var hi = samples.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timeNs <= timeNs) lo = mid else hi = mid
        }
        val a = samples[lo]
        if (a.timeNs == timeNs || lo == samples.lastIndex) return a.logRatio
        val b = samples[lo + 1]
        val dt = b.timeNs - a.timeNs
        if (dt <= 0L) return a.logRatio
        val f = (timeNs - a.timeNs).toDouble() / dt
        return a.logRatio + (b.logRatio - a.logRatio) * f
    }

    private fun viewportFrameP95(r: Recorder): Double {
        if (!r.clockOffsetNs.isFinite()) return Double.NaN
        val times = r.viewport.map { (it.jsMs * 1_000_000.0 + r.clockOffsetNs).toLong() }
            .filter { it in r.startedNs..r.endedNs }.sorted()
        if (times.size < 8) return Double.NaN
        val deltas = ArrayList<Double>(times.size - 1)
        for (i in 1 until times.size) if (times[i] > times[i - 1]) deltas += (times[i] - times[i - 1]) / 1_000_000.0
        return percentile(deltas, .95)
    }

    private fun complete(state: RunState) {
        if (run !== state) return
        val decisions = decisions(state)
        val overall = overall(decisions)
        val report = humanReport(state, decisions, overall)
        val json = jsonReport(state, decisions, overall)
        val saved = saveReports(state.app, report, json)
        val summary = "$overall\n${decisions.joinToString("\n") { "${it.name}: ${it.verdict}" }}\n${if (saved.isBlank()) "Saved in app benchmark files." else "Saved: $saved"}"
        state.app.getSharedPreferences("pinch-benchmark", Context.MODE_PRIVATE).edit()
            .putString("latest-summary", summary).apply()
        run = null
        pending = null
        active = null
        PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
        RendererArena.transport = RendererArena.Transport.DIRECT_GECKO_SURFACE
        BubbleService.active?.window?.setRendererTransportForArena(RendererArena.Transport.DIRECT_GECKO_SURFACE)
        Toast.makeText(state.app, "Hardcore pinch benchmark complete. Opening the summary.", Toast.LENGTH_LONG).show()
        main.postDelayed({
            state.app.startActivity(Intent(state.app, RendererArenaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, 700L)
    }

    private fun decisions(state: RunState): List<MetricDecision> {
        val pairs = (0 until state.pairs).mapNotNull { pair ->
            val blocks = state.results.filter { it.pair == pair }
            val direct = blocks.firstOrNull { it.transport == RendererArena.Transport.DIRECT_GECKO_SURFACE }
            val relay = blocks.firstOrNull { it.transport == RendererArena.Transport.RELAY_LATEST_BP }
            if (direct != null && relay != null) direct to relay else null
        }
        fun metric(name: String, unit: String, threshold: Double, value: (BlockResult) -> Double): MetricDecision {
            val diffs = pairs.mapNotNull { (d, r) ->
                val a = value(d); val b = value(r)
                if (a.isFinite() && b.isFinite()) a - b else null
            }
            if (diffs.isEmpty()) return MetricDecision(name, unit, Double.NaN, Double.NaN, Double.NaN, threshold, "UNAVAILABLE")
            val center = median(diffs)
            val ci = bootstrapMedianCi(diffs, state.seed xor name.hashCode().toLong())
            val verdict = when {
                ci.second < -threshold -> "DIRECT WIN"
                ci.first > threshold -> "RELAY WIN"
                ci.first >= -threshold && ci.second <= threshold -> "EQUIVALENT"
                else -> "INCONCLUSIVE"
            }
            return MetricDecision(name, unit, center, ci.first, ci.second, threshold, verdict)
        }
        val pooledCpu = pairs.flatMap { listOf(it.first.appCpuMsPerSec, it.second.appCpuMsPerSec) }.filter { it.isFinite() }
        val cpuBound = max(2.0, (pooledCpu.average().takeIf { it.isFinite() } ?: 0.0) * .03)
        return listOf(
            metric("Pinch span → VisualViewport lag", "ms", 1.0) { it.trackingLagMs },
            metric("Pinch scale tracking error", "%", .50) { it.trackingErrorPct },
            metric("VisualViewport rAF frame p95", "ms", .25) { it.viewportFrameP95Ms },
            metric("Bubble process CPU", "ms CPU / s", cpuBound) { it.appCpuMsPerSec },
            metric("Bubble main-thread CPU", "ms CPU / s", max(.75, cpuBound / 2)) { it.mainCpuMsPerSec },
            metric("Input event age p95 (control)", "ms", .25) { it.eventAgeP95Ms },
            metric("Shared input preamble p95 (control)", "ms", .10) { it.preambleP95Ms }
        )
    }

    private fun overall(d: List<MetricDecision>): String {
        val primary = d.take(3).filter { it.verdict != "UNAVAILABLE" }
        val direct = primary.count { it.verdict == "DIRECT WIN" }
        val relay = primary.count { it.verdict == "RELAY WIN" }
        val equivalent = primary.count { it.verdict == "EQUIVALENT" }
        return when {
            direct >= 2 && relay == 0 -> "Overall pinch result: DIRECT favored on zoom-response metrics"
            relay >= 2 && direct == 0 -> "Overall pinch result: RELAY favored on zoom-response metrics"
            primary.isNotEmpty() && equivalent == primary.size -> "Overall pinch result: equivalent within predefined zoom bounds"
            else -> "Overall pinch result: mixed / inconclusive"
        }
    }

    private fun workloadWarnings(state: RunState): List<String> {
        val out = ArrayList<String>()
        repeat(state.pairs) { pair ->
            val b = state.results.filter { it.pair == pair }
            val d = b.firstOrNull { it.transport == RendererArena.Transport.DIRECT_GECKO_SURFACE }
            val r = b.firstOrNull { it.transport == RendererArena.Transport.RELAY_LATEST_BP }
            if (d != null && r != null) {
                fun mismatch(a: Double, c: Double): Double = abs(a - c) / max(max(abs(a), abs(c)), 1.0)
                if (mismatch(d.spanTravelPx, r.spanTravelPx) > .25 || mismatch(d.centroidTravelPx, r.centroidTravelPx) > .40 ||
                    abs(d.gestures - r.gestures) > 1) {
                    out += "Pair ${pair + 1}: pinch workload differed (span travel ${fmt(d.spanTravelPx)}/${fmt(r.spanTravelPx)} px, centroid ${fmt(d.centroidTravelPx)}/${fmt(r.centroidTravelPx)} px, gestures ${d.gestures}/${r.gestures}); interpret that pair cautiously."
                }
                if (d.extraPointerEvents > 0 || r.extraPointerEvents > 0) out += "Pair ${pair + 1}: 3+ finger events were observed; use exactly two fingers next run for cleaner data."
            }
        }
        return out
    }

    private fun humanReport(state: RunState, decisions: List<MetricDecision>, overall: String): String = buildString {
        appendLine("Bubble hardcore pinch/zoom benchmark")
        appendLine("Generated: ${java.util.Date()}")
        appendLine("Pairs: ${state.pairs}; blocks: ${state.results.size}; seed: ${state.seed}")
        appendLine("Input policy: UNBUFFERED_140 for BOTH renderer arms")
        appendLine("Renderer A/B: DIRECT GeckoView SurfaceView vs relay_latest_bp")
        appendLine("Block target: $TARGET_GESTURES complete physical two-finger gestures")
        appendLine()
        appendLine(overall)
        appendLine()
        appendLine("PAIRED DECISIONS (direct minus relay; lower is better)")
        decisions.forEach { m ->
            appendLine("${m.name}: ${fmt(m.medianDirectMinusRelay)} ${m.unit}; 95% bootstrap CI [${fmt(m.ciLow)}, ${fmt(m.ciHigh)}], practical bound ±${fmt(m.threshold)} => ${m.verdict}")
        }
        val warnings = workloadWarnings(state)
        if (warnings.isNotEmpty()) {
            appendLine(); appendLine("WORKLOAD PARITY WARNINGS"); warnings.forEach(::appendLine)
        }
        appendLine(); appendLine("RAW BLOCKS")
        state.results.forEach { b ->
            appendLine("block=${b.index + 1} pair=${b.pair + 1} method=${methodLabel(b.transport)} duration=${fmt(b.durationMs)}ms gestures=${b.gestures} events=${b.events} moves=${b.moves} history=${b.historySamples} extraPointerEvents=${b.extraPointerEvents} eventAgeP95=${fmt(b.eventAgeP95Ms)}ms preambleP95=${fmt(b.preambleP95Ms)}ms sourceSpacingP95=${fmt(b.sourceSpacingP95Ms)}ms spanTravel=${fmt(b.spanTravelPx)}px centroidTravel=${fmt(b.centroidTravelPx)}px spanRange=${fmt(b.spanRange)}x reversals=${b.reversals} viewportSamples=${b.viewportSamples} viewportFrameP95=${fmt(b.viewportFrameP95Ms)}ms trackingLag=${fmt(b.trackingLagMs)}ms trackingError=${fmt(b.trackingErrorPct)}% syncRtt=${fmt(b.clockSyncRttMs)}ms cpu=${fmt(b.appCpuMsPerSec)}ms/s mainCpu=${fmt(b.mainCpuMsPerSec)}ms/s pss=${b.pssKb}KB heap=${b.heapUsedKb}KB net=${b.rxBytes}/${b.txBytes} relaySubmit=${b.relaySubmitted} relayRelease=${b.relayReleased} relayErrors=${b.relayErrors} routeValid=${b.routeValid} forced=${b.forcedFinish}")
        }
        appendLine(); appendLine("SCOPE / HONESTY")
        appendLine("- Physical MotionEvent span is the real two-finger input seen by Bubble/Gecko; blocks start on ACTION_POINTER_DOWN, not a synthetic gesture.")
        appendLine("- VisualViewport is sampled by a benchmark-only rAF loop with NO page touch listeners. It measures Gecko/page zoom state, not OLED presentation.")
        appendLine("- A best-RTT native<->page clock sync maps performance.now() into System.nanoTime(); sync RTT is reported so poor clock evidence is visible.")
        appendLine("- Tracking lag is the lag that minimizes log-scale error between actual finger-span ratio and VisualViewport.scale. It is software zoom-response timing, NOT finger-contact-to-photon.")
        appendLine("- Workload parity uses actual span/centroid travel; human gestures are never assumed identical just because block durations match.")
        appendLine("- Relay counters are route proof: Direct must submit zero relay frames; Relay must submit frames.")
    }

    private fun jsonReport(state: RunState, decisions: List<MetricDecision>, overall: String): String {
        val root = JSONObject().put("generatedWallMs", System.currentTimeMillis()).put("pairs", state.pairs)
            .put("seed", state.seed).put("overall", overall).put("targetGestures", TARGET_GESTURES)
            .put("inputPolicy", "UNBUFFERED_140").put("workloadWarnings", JSONArray(workloadWarnings(state)))
        val metrics = JSONArray()
        decisions.forEach { m -> metrics.put(JSONObject().put("name", m.name).put("unit", m.unit)
            .put("directMinusRelay", finiteOrNull(m.medianDirectMinusRelay)).put("ciLow", finiteOrNull(m.ciLow))
            .put("ciHigh", finiteOrNull(m.ciHigh)).put("threshold", m.threshold).put("verdict", m.verdict)) }
        root.put("decisions", metrics)
        val blocks = JSONArray()
        state.results.forEach { b -> blocks.put(JSONObject()
            .put("index", b.index).put("pair", b.pair).put("renderer", b.transport.name)
            .put("durationMs", b.durationMs).put("gestures", b.gestures).put("events", b.events).put("moves", b.moves)
            .put("historySamples", b.historySamples).put("extraPointerEvents", b.extraPointerEvents)
            .put("eventAgeP95Ms", finiteOrNull(b.eventAgeP95Ms)).put("preambleP95Ms", finiteOrNull(b.preambleP95Ms))
            .put("sourceSpacingP95Ms", finiteOrNull(b.sourceSpacingP95Ms)).put("spanTravelPx", b.spanTravelPx)
            .put("centroidTravelPx", b.centroidTravelPx).put("spanRange", finiteOrNull(b.spanRange)).put("reversals", b.reversals)
            .put("viewportSamples", b.viewportSamples).put("viewportFrameP95Ms", finiteOrNull(b.viewportFrameP95Ms))
            .put("trackingLagMs", finiteOrNull(b.trackingLagMs)).put("trackingErrorPct", finiteOrNull(b.trackingErrorPct))
            .put("clockSyncRttMs", finiteOrNull(b.clockSyncRttMs)).put("appCpuMsPerSec", b.appCpuMsPerSec)
            .put("mainCpuMsPerSec", b.mainCpuMsPerSec).put("pssKb", b.pssKb).put("heapUsedKb", b.heapUsedKb)
            .put("relaySubmitted", b.relaySubmitted).put("relayReleased", b.relayReleased).put("relayErrors", b.relayErrors)
            .put("routeValid", b.routeValid).put("forcedFinish", b.forcedFinish)) }
        root.put("blocks", blocks)
        root.put("scope", "Real physical pinch input plus synchronized VisualViewport software zoom state. Not touch-to-photon.")
        return root.toString(2)
    }

    private fun saveReports(context: Context, text: String, json: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = File(context.getExternalFilesDir(null), "pinch-benchmarks").apply { mkdirs() }
        File(dir, "pinch-$stamp.txt").writeText(text)
        File(dir, "pinch-$stamp.json").writeText(json)
        if (Build.VERSION.SDK_INT < 29) return dir.absolutePath
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Bubble-pinch-benchmark-$stamp.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bubble Benchmarks")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching ""
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/Bubble Benchmarks/Bubble-pinch-benchmark-$stamp.txt"
        }.getOrDefault("")
    }

    private fun endSnapshot(): EndSnapshot {
        val runtime = Runtime.getRuntime()
        return EndSnapshot(
            Process.getElapsedCpuTime(), Debug.threadCpuTimeNanos(), safeUidRx(), safeUidTx(),
            Debug.getPss(), runtime.totalMemory() - runtime.freeMemory(), relayStats()
        )
    }

    private fun matchesActiveInput(view: View, transport: RendererArena.Transport): Boolean {
        val window = BubbleService.active?.window ?: return false
        val host = window.pageHost ?: return false
        return window.mode == FloatingMode.CHAT && host.transport == transport && host.pageView === view &&
            view.isAttachedToWindow && view.hasWindowFocus()
    }

    private fun relayStats(): LongArray = runCatching { NativeAhbBridge.nativeDebugStats() }.getOrElse { LongArray(10) }
    private fun delta(end: LongArray, start: LongArray, index: Int): Long =
        (end.getOrElse(index) { 0L } - start.getOrElse(index) { 0L }).coerceAtLeast(0L)
    private fun safeUidRx(): Long = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
    private fun safeUidTx(): Long = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
    private fun methodLabel(t: RendererArena.Transport) = if (t == RendererArena.Transport.DIRECT_GECKO_SURFACE) "DIRECT" else "RELAY"
    private fun finiteOrNull(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL
    private fun fmt(v: Double): String = if (v.isFinite()) String.format(java.util.Locale.US, "%.3f", v) else "unavailable"

    private fun percentile(values: List<Double>, q: Double): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val pos = (sorted.lastIndex * q.coerceIn(0.0, 1.0))
        val lo = pos.toInt(); val hi = min(lo + 1, sorted.lastIndex); val f = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * f
    }
    private fun median(values: List<Double>): Double = percentile(values, .5)
    private fun bootstrapMedianCi(values: List<Double>, seed: Long): Pair<Double, Double> {
        if (values.size < 2) return values.firstOrNull()?.let { it to it } ?: (Double.NaN to Double.NaN)
        val random = Random(seed)
        val medians = DoubleArray(4000)
        val sample = ArrayList<Double>(values.size)
        for (i in medians.indices) {
            sample.clear()
            repeat(values.size) { sample += values[random.nextInt(values.size)] }
            medians[i] = median(sample)
        }
        medians.sort()
        return medians[(medians.size * .025).toInt().coerceIn(medians.indices)] to
            medians[(medians.size * .975).toInt().coerceIn(medians.indices)]
    }
}
