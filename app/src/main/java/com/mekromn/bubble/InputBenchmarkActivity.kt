package com.mekromn.bubble

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Control plane only. The measured content stays in BrowserActivity or the real
 * floating Bubble window. This Activity closes before measured blocks begin.
 */
class InputBenchmarkActivity : Activity() {
    private lateinit var summary: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "Bubble 139 ↔ 140 A/B"
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 12))
        }
        scroll.addView(column)
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(235, 238, 244)); setPadding(0, dp(7), 0, dp(7))
        }
        column.addView(text("Production-window input benchmark", 22f))
        column.addView(text("One APK contains the exact 139 buffered policy and 140 gesture-unbuffered policy. Paired blocks switch only that policy; the Gecko engine, relay_latest_bp renderer, hardware flags, profile, windows and controls stay shared."))
        column.addView(text("How to use: put Bubble on the real site and window mode you want to judge, open this Benchmark icon, start the matching suite, then interact with the PAGE continuously during measured blocks. Do not tap any confirmation button—there isn't one."))
        column.addView(button("Open deterministic stress page") {
            val url = BenchmarkPageServer.ensureStarted(applicationContext)
            startActivity(Intent(this, BrowserActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
            finish()
        })
        column.addView(button("Prepare floating mode") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Enable overlay permission, return to Bubble, then collapse to the floating chat.", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, BrowserActivity::class.java))
                Toast.makeText(this, "Collapse Bubble to its normal floating chat, then open the Benchmark icon again.", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        column.addView(text("Paired suites (8 independent pairs × 2 arms; ~4 minutes):", 17f))
        column.addView(button("Run FULLSCREEN 139 ↔ 140 paired suite") {
            startSuite(InputBenchmark.HOST_FULLSCREEN)
            startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
        })
        column.addView(button("Run FLOATING 139 ↔ 140 paired suite") {
            if (BubbleService.active?.window == null) {
                AlertDialog.Builder(this).setTitle("Floating Bubble not active")
                    .setMessage("First put the real Bubble page in floating chat mode. Then open this Benchmark icon and start the floating suite. The benchmark intentionally does not substitute a fake test window.")
                    .setPositiveButton("OK", null).show()
            } else {
                startSuite(InputBenchmark.HOST_FLOATING); finish()
            }
        })
        column.addView(button("Run CURRENT/MIXED suite (exploratory)") { startSuite(InputBenchmark.HOST_ANY); finish() })
        column.addView(text("Manual arena (not statistically scored):", 17f))
        column.addView(button("Force 139 buffered now") { InputBenchmark.setManualArm(PageTouchDispatch.Arm.BUFFERED_139); toast("139 buffered active") })
        column.addView(button("Force 140 unbuffered now") { InputBenchmark.setManualArm(PageTouchDispatch.Arm.UNBUFFERED_140); toast("140 unbuffered active") })
        column.addView(button("Experimental adaptive: buffered finger + unbuffered stylus") { InputBenchmark.setManualArm(PageTouchDispatch.Arm.STYLUS_ONLY); toast("Adaptive stylus-only active") })
        column.addView(button("Stop/mark current suite incomplete") { InputBenchmark.stop(applicationContext); toast("Benchmark stopped; partial data exported if available") })
        column.addView(text("Last completed analysis", 19f))
        summary = text(InputBenchmark.lastSummary(this), 13f).apply {
            setTextIsSelectable(true); setBackgroundColor(Color.rgb(18, 20, 27)); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        column.addView(text("Exports are written to Downloads/Bubble Benchmarks as summary text + one raw block-row CSV. Reopen this icon after completion to see the winner without importing anything."))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::summary.isInitialized) summary.text = InputBenchmark.lastSummary(this)
    }

    private fun startSuite(host: Int) {
        InputBenchmark.startPairedSuite(applicationContext, host)
        toast("Suite armed. There is a 6-second setup delay before Pair 1.")
    }

    private fun button(label: String, action: (View) -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
        setOnClickListener(action)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    private fun dp(n: Int): Int = (resources.displayMetrics.density * n + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
