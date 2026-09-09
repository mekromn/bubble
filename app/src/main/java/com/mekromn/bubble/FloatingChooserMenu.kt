package com.mekromn.bubble

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Compact replacement for the old three-button chooser footer. */
internal object FloatingChooserMenu {
    fun show(anchor: View, workspace: Workspace, openChat: (String) -> Unit) {
        val panel = QuickPanel.open(anchor, workspace, "Workspace menu", 300) ?: return
        fun d(n: Int) = Ui.dp(anchor.context, n.toFloat())
        val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), d(6), d(8), d(8)) }
        panel.body.addView(body, LinearLayout.LayoutParams(-1, -1))
        fun row(label: String, action: () -> Unit) = Ui.text(anchor.context, label, 14f, Ui.ACCENT, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(14), 0, d(14), 0)
            isClickable = true; isFocusable = true; background = Ui.ripple(anchor.context, Color.TRANSPARENT, 14f)
            setOnClickListener { panel.finish(action) }
        }
        body.addView(row("Chat tools") { QuickMenus.tools(anchor, workspace, openChat) }, LinearLayout.LayoutParams(-1, d(56)))
        body.addView(row("Continuity Vault · saved chats") { ChatVaultUi.show(anchor, workspace, openChat) }, LinearLayout.LayoutParams(-1, d(56)))
        body.addView(row("Edge access") { AccessMenu.show(anchor, workspace) }, LinearLayout.LayoutParams(-1, d(56)))
        body.addView(row("Reply sound / ChatGPT notifications") { Replies.settings(anchor.context) }, LinearLayout.LayoutParams(-1, d(56)))
    }
}
