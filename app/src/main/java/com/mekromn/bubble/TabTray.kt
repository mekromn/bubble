package com.mekromn.bubble

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

/** Compact workspace sheet. It is native UI only, so its temporary animation layer is safe. */
internal class TabTray(c: Context, select: (String) -> Unit, close: (String) -> Unit,
    newChat: () -> Unit, dismiss: () -> Unit) : LinearLayout(c) {
    private val conversations = ConversationList(c, select, close)
    private val count = Ui.text(c, "", 12f, Ui.MUTED)
    init {
        orientation = VERTICAL; setBackgroundColor(Ui.BG); isClickable = true; isFocusable = true
        setPadding(d(12), d(8), d(12), d(12))
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(c).apply { orientation = VERTICAL; setPadding(d(8), 0, 0, 0) }
        labels.addView(Ui.text(c, "Your workspace", 23f, Ui.TEXT, true))
        count.setPadding(0, d(4), 0, 0); labels.addView(count)
        header.addView(labels, LayoutParams(0, -2, 1f))
        header.addView(GlyphView(c, "close", "Close workspace").apply { setOnClickListener { dismiss() } }, LayoutParams(d(48), d(48)))
        addView(header, LayoutParams(-1, d(60)))
        val search = EditText(c).apply {
            hint = "Find a conversation"; contentDescription = "Find a conversation"; setSingleLine(true)
            textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            setPadding(d(16), 0, d(16), 0); background = Ui.shape(c, Ui.SURFACE, 18f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { conversations.search(s.toString()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LayoutParams(-1, d(48)).apply { setMargins(d(8), d(10), d(8), d(8)) })
        addView(conversations, LayoutParams(-1, 0, 1f))
        addView(Ui.text(c, "＋  New ChatGPT chat", 15f, Ui.BG, true).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(c, Ui.BLUE, 24f); setOnClickListener { newChat() }
        }, LayoutParams(-1, d(50)).apply { setMargins(d(8), d(8), d(8), 0) })
    }
    fun refresh(workspace: Workspace) {
        val value = "${workspace.tabs.size} tabs · all kept live"
        if (count.text != value) count.text = value
        conversations.refresh(workspace)
    }
    private fun d(n: Int) = Ui.dp(context, n.toFloat())
}
