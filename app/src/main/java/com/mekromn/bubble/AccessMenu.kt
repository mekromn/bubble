package com.mekromn.bubble

import android.view.Gravity
import android.view.View
import android.widget.*

/** Explicit opt-in, bounded edge configuration. Nothing auto-launches when a preference is loaded. */
internal object AccessMenu {
    fun show(anchor: View, workspace: Workspace) {
        val preferences = AccessPreferences.get(anchor.context)
        if (!preferences.ready) { Toast.makeText(anchor.context, "Settings are loading. Try again.", Toast.LENGTH_SHORT).show(); return }
        val panel = QuickPanel.open(anchor, workspace, "Bubble / edge access", 550) ?: return
        val c = anchor.context
        val original = preferences.options
        fun d(n: Int) = Ui.dp(c, n.toFloat())
        val scroll = ScrollView(c)
        val form = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, d(12), d(6)) }
        scroll.addView(form); panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val enabled = Switch(c).apply { text = "Use edge gestures instead of bubble"; textSize = 14f; setTextColor(Ui.TEXT); isChecked = original.enabled; minHeight = d(56) }
        form.addView(enabled)
        form.addView(Ui.text(c, "Swipe inward from the short side zone to choose a chat. Hold it to open the selected chat. Long-press Minimize hides BOTH bubble and edge until restored from the notification.\n\nThe zone consumes touches and replaces Android Back only in that small area. Choose a location away from controls you use in other apps.", 12f, Ui.MUTED).apply { setPadding(0, d(6), 0, d(10)) })
        val sides = RadioGroup(c).apply { orientation = RadioGroup.HORIZONTAL }
        val left = RadioButton(c).apply { id = View.generateViewId(); text = "Left edge"; setTextColor(Ui.TEXT) }
        val right = RadioButton(c).apply { id = View.generateViewId(); text = "Right edge"; setTextColor(Ui.TEXT) }
        sides.addView(left, RadioGroup.LayoutParams(0, d(48), 1f)); sides.addView(right, RadioGroup.LayoutParams(0, d(48), 1f))
        sides.check(if (original.left) left.id else right.id); form.addView(sides)
        fun slider(label: String, min: Int, max: Int, value: Int): SeekBar {
            val text = Ui.text(c, "$label: $value", 13f).apply { setPadding(0, d(10), 0, 0) }
            val seek = SeekBar(c).apply { this.max = max - min; progress = value - min; contentDescription = label }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v: SeekBar?, p: Int, user: Boolean) { text.text = "$label: ${min + p}" }
                override fun onStartTrackingTouch(v: SeekBar?) = Unit
                override fun onStopTrackingTouch(v: SeekBar?) = Unit
            })
            form.addView(text); form.addView(seek, LinearLayout.LayoutParams(-1, d(48))); return seek
        }
        val position = slider("Vertical position (%)", 0, 100, (original.position * 100).toInt())
        val height = slider("Zone height (dp)", 64, 160, original.heightDp)
        val width = slider("Zone width (dp)", 12, 28, original.widthDp)
        val indicator = Switch(c).apply { text = "Show faint grey edge indicator"; setTextColor(Ui.TEXT); textSize = 14f; isChecked = original.indicator; minHeight = d(52) }
        form.addView(indicator)
        panel.body.addView(Ui.text(c, "Save access settings", 15f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(c, Ui.SURFACE_HIGH, 18f)
            setOnClickListener {
                preferences.update(EdgeOptions(enabled.isChecked, sides.checkedRadioButtonId == left.id,
                    position.progress / 100f, 64 + height.progress, 12 + width.progress, indicator.isChecked)) { saved ->
                    Toast.makeText(c, if (saved) "Saved. Minimize to use ${if (enabled.isChecked) "the edge" else "the bubble"}." else preferences.error ?: "Settings could not be saved.", Toast.LENGTH_LONG).show()
                }
                panel.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, d(48)))
    }
}
