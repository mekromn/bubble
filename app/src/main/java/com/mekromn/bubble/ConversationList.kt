package com.mekromn.bubble

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** The same compact conversation chooser is used in the overlay and fullscreen workspace. */
internal class ConversationList(context: Context, private val select: (String) -> Unit,
    private val close: (String) -> Unit) : RecyclerView(context) {
    private data class Row(val id: String, val title: String, val subtitle: String,
        val selected: Boolean, val unread: Boolean, val busy: Boolean)
    private var rows = emptyList<Row>()
    private var query = ""
    private val cards = Rows()
    init {
        layoutManager = LinearLayoutManager(context)
        adapter = cards
        itemAnimator = null // Stream-state updates must not blink or restart card animations.
        clipToPadding = false
        setPadding(d(8), d(4), d(8), d(8))
        contentDescription = "Conversation list"
    }
    fun refresh(workspace: Workspace) {
        val next = workspace.tabs.map { tab -> Row(tab.id, tab.title.ifBlank { "New ChatGPT chat" },
            when {
                tab.error != null -> "Needs attention · tap to open"
                tab.generating -> "Generating a reply"
                tab.unread -> "New reply · ready to read"
                tab.loading -> "Loading · ${Policy.host(tab.url)}"
                else -> "Live · ${Policy.host(tab.url)}"
            }, tab.id == workspace.selectedId, tab.unread, tab.generating) }
        if (rows == next) return
        rows = next
        submit()
    }
    fun search(value: String) { query = value.trim(); submit() }
    private fun submit() { cards.submitList(rows.filter { it.title.contains(query, true) || it.subtitle.contains(query, true) }) }
    private fun d(value: Int) = Ui.dp(context, value.toFloat())
    private inner class Holder(val row: LinearLayout, val icon: GlyphView, val title: TextView,
        val subtitle: TextView, val closeButton: GlyphView) : ViewHolder(row)
    private inner class Rows : ListAdapter<Row, Holder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(8), d(8), d(4), d(8))
                layoutParams = LayoutParams(-1, -2).apply { setMargins(0, d(4), 0, d(4)) }
                minimumHeight = d(76)
            }
            val icon = GlyphView(c, "bubble", "", true).apply {
                isClickable = false; isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon, LinearLayout.LayoutParams(d(40), d(44)))
            val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(4), 0) }
            val title = Ui.text(c, "", 14f, Ui.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val subtitle = Ui.text(c, "", 11f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d(5), 0, 0) }
            text.addView(title); text.addView(subtitle)
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val close = GlyphView(c, "close", "Close conversation")
            row.addView(close, LinearLayout.LayoutParams(d(48), d(48)))
            return Holder(row, icon, title, subtitle, close)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.row.background = Ui.ripple(context, if (row.selected) Ui.SURFACE_HIGH else Ui.SURFACE, 20f)
            holder.title.text = row.title
            holder.subtitle.text = row.subtitle
            holder.subtitle.setTextColor(if (row.unread || row.busy) Ui.MINT else Ui.MUTED)
            holder.row.contentDescription = "${row.title}, ${row.subtitle}${if (row.selected) ", selected" else ""}"
            holder.row.setOnClickListener { select(row.id) }
            holder.closeButton.contentDescription = "Close ${row.title}"
            holder.closeButton.setOnClickListener { close(row.id) }
        }
    }
}
