package com.mekromn.bubble

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** Matched entry/exit: the controls arrive from and return to the bottom edge. */
internal object ControlsSheet {
    fun show(activity: Activity, title: String, actions: List<Pair<String, () -> Unit>>) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(activity, Ui.SURFACE, 28f, Ui.LINE)
            setPadding(dp(activity, 12), dp(activity, 14), dp(activity, 12), dp(activity, 20))
        }
        val dialog = object : Dialog(activity) {
            private var closing = false
            override fun dismiss() {
                if (closing) return
                if (!isShowing || !ValueAnimator.areAnimatorsEnabled() || activity.isFinishing) { super.dismiss(); return }
                closing = true
                content.animate().cancel()
                content.animate().withLayer().alpha(0f).translationY(dp(activity, 24).toFloat())
                    .setDuration(150).setInterpolator(Ui.ease).withEndAction { super.dismiss() }.start()
            }
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
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM); setDimAmount(.32f) }
        dialog.show(); dialog.window?.setLayout(-1, -2)
        if (ValueAnimator.areAnimatorsEnabled()) {
            content.alpha = 0f; content.translationY = dp(activity, 24).toFloat()
            content.animate().withLayer().alpha(1f).translationY(0f).setDuration(210).setInterpolator(Ui.ease).start()
        }
    }
    private fun dp(a: Activity, n: Int) = Ui.dp(a, n.toFloat())
}
