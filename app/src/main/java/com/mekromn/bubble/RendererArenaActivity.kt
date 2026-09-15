package com.mekromn.bubble

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Production renderer + physical input benchmark controller. */
class RendererArenaActivity : Activity() {
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            if (!isFinishing) main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18f * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.TOP
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        column.addView(label(
            "Renderer A/B uses the same app process, profile/cache, Gecko engine, GeckoSession, real floating window/chrome, hardware policy and Build-140 unbuffered touch policy. Only the steady-state floating renderer changes."
        ))
        addArm(column, "A · relay_latest_bp baseline", RendererArena.Transport.RELAY_LATEST_BP)
        addArm(column, "B · direct Gecko SurfaceView", RendererArena.Transport.DIRECT_GECKO_SURFACE)

        column.addView(label(
            "SCROLLING PHYSICAL A/B\nOpen the real floating page you want to test, then start a run here. This controller closes. For every block, wait for the instruction, then repeat the same natural up/down scrolling workload. Method order is counterbalanced and hidden."
        ))
        addScrollBenchmark(column, "Quick scrolling A/B · 4 pairs", 4)
        addScrollBenchmark(column, "Rigorous scrolling A/B · 8 pairs", 8)
        column.addView(Button(this).apply {
            text = "Cancel scrolling run · restore direct"
            isAllCaps = false
            setOnClickListener {
                RendererBenchmark.cancel()
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(label(
            "HARDCORE PINCH / ZOOM A/B\nThis is a different test, not scroll callbacks renamed. Each hidden-renderer block starts only when your second physical finger touches the real Gecko page. Do five aggressive two-finger zoom gestures: large in/out travel, reversals, natural speed, lifting both fingers between gestures. Bubble records the actual finger-span trajectory and synchronizes it to VisualViewport.scale, so human gesture differences are measured instead of assumed equal. No page touch listener is installed by the sampler."
        ))
        addPinchBenchmark(column, "Quick hardcore pinch A/B · 3 pairs", 3)
        addPinchBenchmark(column, "Rigorous hardcore pinch A/B · 6 pairs", 6)
        column.addView(Button(this).apply {
            text = "Cancel pinch run · restore direct"
            isAllCaps = false
            setOnClickListener {
                PinchBenchmark.cancel()
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(label(
            "Pinch reports include actual input span/centroid travel, source-sample spacing/history, input event age, synchronized VisualViewport scale response, scale-tracking error, viewport rAF cadence, Bubble CPU/memory and relay route proof. The synchronized span→VisualViewport result is software zoom-response timing; it is still not OLED touch-to-photon."
        ))
        column.addView(Button(this).apply {
            text = "Close controller"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(column) })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(0xffbdbdbd.toInt())
        textSize = 13f
        setPadding(0, 14, 0, 14)
    }

    private fun addArm(root: LinearLayout, title: String, transport: RendererArena.Transport) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                if (RendererBenchmark.status(this@RendererArenaActivity).running) {
                    RendererBenchmark.cancel("Scrolling run canceled before manual renderer switching.")
                }
                if (PinchBenchmark.status(this@RendererArenaActivity).running) {
                    PinchBenchmark.cancel("Pinch run canceled before manual renderer switching.")
                }
                PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
                RendererArena.transport = transport
                BubbleService.active?.window?.setRendererTransportForArena(transport)
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addScrollBenchmark(root: LinearLayout, title: String, pairs: Int) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                if (RendererBenchmark.start(this@RendererArenaActivity, pairs)) finish()
                else refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addPinchBenchmark(root: LinearLayout, title: String, pairs: Int) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                if (PinchBenchmark.start(this@RendererArenaActivity, pairs)) finish()
                else refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val window = BubbleService.active?.window
        val activeRenderer = window?.pageHost?.transport
        val scrolling = RendererBenchmark.status(this)
        val pinch = PinchBenchmark.status(this)
        status.text = buildString {
            append("Requested renderer: ").append(RendererArena.transport.name).append('\n')
            append("Active floating renderer: ").append(activeRenderer?.name ?: "none").append('\n')
            append("Input: ").append(PageTouchDispatch.arm.shortLabel).append(" (fixed to 140 during renderer A/B)\n")
            if (scrolling.running) {
                append("Scrolling run: block ").append(scrolling.block).append('/').append(scrolling.totalBlocks)
                    .append(" · ").append(scrolling.phase).append('\n')
            } else append("Scrolling run: idle\n")
            if (pinch.running) {
                append("Pinch run: block ").append(pinch.block).append('/').append(pinch.totalBlocks)
                    .append(" · ").append(pinch.phase).append('\n')
            } else append("Pinch run: idle\n")
            append(if (window != null) "Floating Bubble is live." else "Bubble is not floating; open a floating http/https page before a measured run.")
            append("\n\nLatest scrolling result:\n").append(scrolling.latestSummary)
            append("\n\nLatest pinch result:\n").append(pinch.latestSummary)
        }
    }
}
