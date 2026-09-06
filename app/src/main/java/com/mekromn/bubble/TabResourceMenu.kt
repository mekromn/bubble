package com.mekromn.bubble

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

/** Manual resource controls live one long-press away from every tab row. Automatic suspension is
 * the default for idle background ChatGPT tabs; Google Voice is protected live while its tab exists. */
internal object TabResourceMenu {
    fun show(anchor: View, workspace: Workspace, id: String, select: (String) -> Unit, more: () -> Unit) {
        val tab = workspace.tabs.firstOrNull { it.id == id } ?: return
        val voice = Policy.isVoice(tab.url)
        val panel = QuickPanel.open(anchor, workspace, "Resources · ${tab.displayName}", if (voice) 270 else 310) ?: return
        val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
        val state = when {
            voice -> "Google Voice · protected live for calls/messages/voicemail"
            tab.generating -> "Working · protected from suspension"
            tab.loading -> "Loading · protected from suspension"
            tab.forceKeepAlive -> "Forced live · automatic suspension disabled"
            tab.manualSuspended -> "Manually suspended"
            tab.suspended || tab.session == null -> "Suspended · resumes when opened"
            Policy.isChat(tab.url) -> "Live now · idle background state auto-suspends"
            else -> "Live · automatic idle suspension is ChatGPT-only"
        }
        body.addView(Ui.text(anchor.context, state, 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 8))
        }, LinearLayout.LayoutParams(-1, dp(anchor, 46)))
        fun row(label: String, enabled: Boolean = true, action: () -> Unit) = Ui.text(anchor.context, label, 13f, if (enabled) Ui.ACCENT else Ui.MUTED, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 8))
            isEnabled = enabled; alpha = if (enabled) 1f else .45f; isClickable = enabled; isFocusable = true
            background = Ui.ripple(anchor.context, Color.TRANSPARENT, 14f)
            setOnClickListener { if (enabled) panel.finish(action) }
        }
        if (voice) {
            body.addView(row("Google Voice notification controls") { VoiceNotifications.controls(anchor, workspace, id) }, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
            body.addView(row("Protected live · suspension disabled", false) {}, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
        } else {
            body.addView(row(if (tab.forceKeepAlive) "Use automatic suspension" else "Force keep alive · uses more resources") {
                val enable = !tab.forceKeepAlive
                workspace.setForceKeepAlive(id, enable)
                Toast.makeText(anchor.context, if (enable) "Force keep alive enabled for this tab." else "Automatic suspension restored for this tab.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
            val canSuspend = !tab.generating && !tab.loading && !(FileUi.busy && workspace.selectedId == id)
            val isSuspended = tab.manualSuspended || tab.suspended || tab.session == null
            body.addView(row(if (isSuspended) "Resume tab" else "Suspend tab now", isSuspended || canSuspend) {
                if (isSuspended) select(id)
                else if (workspace.suspend(id)) Toast.makeText(anchor.context, "Tab suspended. It resumes when opened.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
        }
        body.addView(row("More tab options") { more() }, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
        panel.body.addView(body, LinearLayout.LayoutParams(-1, -1))
    }
    private fun dp(anchor: View, value: Int) = Ui.dp(anchor.context, value.toFloat())
}
