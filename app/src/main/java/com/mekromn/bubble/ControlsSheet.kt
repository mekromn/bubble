package com.mekromn.bubble

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small themed native sheet; no stock white alert list and no renderer in its animation layer. */
internal object ControlsSheet {
    fun show(activity: Activity, title: String, actions: List<Pair<String, () -> Unit>>) {
        val dialog = Dialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(activity, Ui.SURFACE, 28f, Ui.LINE)
            setPadding(dp(activity, 12), dp(activity, 14), dp(activity, 12), dp(activity, 20))
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(activity, title, 20f, Ui.TEXT, true).apply { setPadding(dp(activity, 12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        heading.addView(GlyphView(activity, "close", "Close controls").apply { setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        content.addView(heading)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { (label, action) ->
            rows.addView(Ui.text(activity, label, 15f).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(activity, 48)
                setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
                background = Ui.ripple(activity, Color.TRANSPARENT, 16f)
                isFocusable = true; setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(activity).apply { addView(rows) }
        val max = (activity.resources.displayMetrics.heightPixels * .65f).toInt()
        content.addView(scroll, LinearLayout.LayoutParams(-1, minOf(max, actions.size * dp(activity, 50))))
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM); setDimAmount(.32f)
            setLayout(-1, -2)
        }
        dialog.show()
        dialog.window?.setLayout(-1, -2)
        Ui.show(content, true)
    }
    private fun dp(a: Activity, n: Int) = Ui.dp(a, n.toFloat())
}
