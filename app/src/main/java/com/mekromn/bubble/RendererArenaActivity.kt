package com.mekromn.bubble

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Production A/B controller. The measured page stays in Bubble's real floating window.
 * Both arms use 140's UNBUFFERED_140 input policy so transport is the only intended variable.
 */
class RendererArenaActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18f * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.TOP
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(label(
            "140 vs 143 renderer A/B. Both arms use the exact same Gecko engine, profile, GeckoSession, floating window/chrome, hardware preferences and 140 unbuffered touch policy. Switching rebinds the active page in-place; it does not reinstall, restart the process, reload the URL or clear caches."
        ))
        addArm(root, "A · 140 baseline — relay_latest_bp", RendererArena.Transport.RELAY_LATEST_BP)
        addArm(root, "B · 143 direct — Gecko SurfaceView", RendererArena.Transport.DIRECT_GECKO_SURFACE)
        root.addView(label(
            "For a fair subjective test: switch, close this controller, wait a moment for the live page, then repeat the same scroll/drag workload. Direct is the production default; relay remains the known-working baseline/fallback."
        ))
        root.addView(Button(this).apply {
            text = "Close controller"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
                // Pin the input half of the experiment to Build 140 for BOTH renderer arms.
                PageTouchDispatch.arm = PageTouchDispatch.Arm.UNBUFFERED_140
                RendererArena.transport = transport
                BubbleService.active?.window?.setRendererTransportForArena(transport)
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshStatus() {
        val window = BubbleService.active?.window
        val active = window?.pageHost?.transport
        status.text = buildString {
            append("Requested renderer: ").append(RendererArena.transport.name).append('\n')
            append("Active floating renderer: ").append(active?.name ?: "none").append('\n')
            append("Input: ").append(PageTouchDispatch.arm.shortLabel).append(" (fixed for A/B)\n")
            append(if (window != null) "Floating Bubble is live; changes apply in-place." else "Bubble is not floating; selection applies to the next floating chat.")
        }
    }
}
