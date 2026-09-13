package com.mekromn.bubble

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Configuration surface only; measured browsing still happens in Bubble's real windows. */
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
        root.addView(label("Choose an arena arm. The active floating chat is rebound in-place to the same GeckoSession/profile; no install, process restart, or page reload is required."))
        addArm(root, "R1 · relay_latest_bp + 139 buffered", RendererArena.Transport.RELAY_LATEST_BP, PageTouchDispatch.Arm.BUFFERED_139)
        addArm(root, "R2 · relay_latest_bp + 140 unbuffered", RendererArena.Transport.RELAY_LATEST_BP, PageTouchDispatch.Arm.UNBUFFERED_140)
        addArm(root, "R3 · DIRECT Gecko SurfaceView + 139 buffered", RendererArena.Transport.DIRECT_GECKO_SURFACE, PageTouchDispatch.Arm.BUFFERED_139)
        addArm(root, "R4 · DIRECT Gecko SurfaceView + 140 unbuffered", RendererArena.Transport.DIRECT_GECKO_SURFACE, PageTouchDispatch.Arm.UNBUFFERED_140)
        addArm(root, "Extra · DIRECT Gecko + stylus-only unbuffering", RendererArena.Transport.DIRECT_GECKO_SURFACE, PageTouchDispatch.Arm.STYLUS_ONLY)
        root.addView(Button(this).apply {
            text = "Close controller"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        refreshStatus()
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(0xffbdbdbd.toInt())
        textSize = 13f
        setPadding(0, 14, 0, 14)
    }

    private fun addArm(
        root: LinearLayout,
        title: String,
        transport: RendererArena.Transport,
        input: PageTouchDispatch.Arm
    ) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                RendererArena.transport = transport
                PageTouchDispatch.arm = input
                BubbleService.active?.window?.setRendererTransportForArena(transport)
                refreshStatus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshStatus() {
        status.text = "Renderer: ${RendererArena.transport.name}\nInput: ${PageTouchDispatch.arm.shortLabel}\n" +
            if (BubbleService.active?.window != null) "Floating Bubble is live; selection applied now." else "Bubble is not currently floating; selection applies on next floating chat."
    }
}
