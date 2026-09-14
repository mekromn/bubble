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

/**
 * Production renderer A/B + physical benchmark controller.
 * The measured page stays in Bubble's real floating window. Both primary arms use 140's
 * UNBUFFERED_140 input policy so renderer transport is the intended A/B variable.
 */
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
            "Renderer A/B uses the same app process, profile/cache, Gecko engine, GeckoSession, floating window/chrome, hardware policy and Build-140 unbuffered touch policy. Only the steady-state floating renderer changes."
        ))
        addArm(column, "A · relay_latest_bp baseline", RendererArena.Transport.RELAY_LATEST_BP)
        addArm(column, "B · direct Gecko SurfaceView", RendererArena.Transport.DIRECT_GECKO_SURFACE)

        column.addView(label(
            "MEASURED PHYSICAL A/B\nOpen the real floating page you want to test, then start a run here. This controller closes. For every block, wait for the short instruction and vibration, then repeat the same natural up/down scrolling workload. A block begins only on your next fresh physical finger-down. Method order is counterbalanced; method names stay hidden while blocks run. Do not use a confirmation tap during a measured block."
        ))
        addBenchmark(column, "Quick measured A/B · 4 pairs", 4)
        addBenchmark(column, "Rigorous measured A/B · 8 pairs", 8)
        column.addView(Button(this).apply {
            text = "Cancel measured run · restore direct"
            isAllCaps = false
            setOnClickListener {
                RendererBenchmark.cancel()
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(label(
            "The report compares paired input age, the identical pre-Gecko input-policy preamble, Bubble process/main-thread CPU, Bubble Choreographer cadence/jank, memory/GC, thermal/battery context and native relay counters. App-frame timing is not webpage presentation FPS, and input-to-app-frame is not touch-to-photon. True contact-to-photon still requires an external high-speed camera/photodiode; Perfetto/FrameTimeline is the preferred software follow-up for compositor scheduling."
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
                    RendererBenchmark.cancel("Measured run canceled before manual renderer switching.")
                }
                PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
                RendererArena.transport = transport
                BubbleService.active?.window?.setRendererTransportForArena(transport)
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addBenchmark(root: LinearLayout, title: String, pairs: Int) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                if (RendererBenchmark.start(this@RendererArenaActivity, pairs)) finish()
                else refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val window = BubbleService.active?.window
        val active = window?.pageHost?.transport
        val benchmark = RendererBenchmark.status(this)
        status.text = buildString {
            append("Requested renderer: ").append(RendererArena.transport.name).append('\n')
            append("Active floating renderer: ").append(active?.name ?: "none").append('\n')
            append("Input: ").append(PageTouchDispatch.arm.shortLabel).append(" (fixed to 140 during renderer A/B)\n")
            if (benchmark.running) {
                append("Measured run: block ").append(benchmark.block).append('/').append(benchmark.totalBlocks)
                    .append(" · ").append(benchmark.phase).append('\n')
            } else append("Measured run: idle\n")
            append(if (window != null) "Floating Bubble is live." else "Bubble is not floating; open a floating chat/page before a measured run.")
            append("\n\nLatest measured result:\n").append(benchmark.latestSummary)
        }
    }
}
