package com.mekromn.bubble

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Recycled shared list: stable logical IDs, persisted user order, pin-first grouping and no status-change blinking. */
internal class ConversationList(context: Context, private val select: (String) -> Unit,
    private val close: (String) -> Unit, private val options: ((View, String) -> Unit)? = null) : RecyclerView(context) {
    private data class Row(val id: String, val title: String, val subtitle: String,
        val selected: Boolean, val unread: Boolean, val busy: Boolean, val pinned: Boolean)
    private var rows = emptyList<Row>(); private var query = ""; private var filter = TabFilter.ALL
    var onResultCount: ((Int) -> Unit)? = null
    private val cards = Rows()
    private val drag = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false
        override fun onSwiped(viewHolder: ViewHolder, direction: Int) = Unit
        override fun onMove(recyclerView: RecyclerView, source: ViewHolder, target: ViewHolder): Boolean {
            val fromPosition = source.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition
            if (fromPosition == NO_POSITION || toPosition == NO_POSITION) return false
            val visible = cards.currentList
            val fromRow = visible.getOrNull(fromPosition) ?: return false
            val toRow = visible.getOrNull(toPosition) ?: return false
            if (fromRow.pinned != toRow.pinned) return false
            val workspace = Workspace.peek() ?: return false
            val from = workspace.tabs.indexOfFirst { it.id == fromRow.id }
            val to = workspace.tabs.indexOfFirst { it.id == toRow.id }
            if (from < 0 || to < 0 || from == to) return false
            val moved = workspace.tabs.removeAt(from)
            workspace.tabs.add(to, moved)
            workspace.changed(true)
            refresh(workspace)
            return true
        }
    })
    init {
        layoutManager = LinearLayoutManager(context); adapter = cards; drag.attachToRecyclerView(this)
        itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false; addDuration = 160; removeDuration = 140; moveDuration = 180; changeDuration = 0 }
        clipToPadding = false; setPadding(d(8), d(4), d(8), d(8)); contentDescription = "Conversation list"
    }
    fun refresh(workspace: Workspace) {
        val next = workspace.tabs.sortedByDescending { it.pinned }.map { tab -> Row(tab.id, tab.displayName,
            (if (workspace.profiles.size > 1) "${workspace.profileName(tab.profileId)} · " else "") +
            (if (tab.pinned) "Pinned · " else "") + when {
                Policy.isVoice(tab.url) && tab.error != null -> "Google Voice · connection needs attention"
                Policy.isVoice(tab.url) && tab.unread -> "Google Voice · new alert · protected live"
                Policy.isVoice(tab.url) -> "Google Voice · protected live"
                tab.error != null && !tab.manualSuspended -> "Needs attention · tap to open"
                tab.generating -> "Generating a reply · kept alive"
                tab.loading -> "Loading · kept alive"
                tab.unread && (tab.suspended || tab.session == null) -> "New reply · suspended until opened"
                tab.unread -> "New reply · ready to read"
                tab.manualSuspended -> "Manually suspended · tap to resume"
                tab.forceKeepAlive -> "Forced live · ${Policy.host(tab.url)}"
                tab.suspended || tab.session == null -> "Suspended · tap to resume"
                else -> "Live · ${Policy.host(tab.url)}${if (tab.muted) " · alerts muted" else ""}"
            }, tab.id == workspace.selectedId, tab.unread, tab.generating || tab.loading, tab.pinned) }
        if (rows == next) return
        rows = next; submit()
    }
    fun search(value: String) { query = value.trim(); submit() }
    fun filter(value: TabFilter) { filter = value; submit() }
    private fun submit() {
        val matched = rows.filter { QuickTabPolicy.accepts(filter, it.unread, it.busy, it.pinned) && (it.title.contains(query, true) || it.subtitle.contains(query, true)) }
        cards.submitList(matched); onResultCount?.invoke(matched.size)
    }
    private fun d(value: Int) = Ui.dp(context, value.toFloat())
    private inner class Holder(val row: LinearLayout, val dragHandle: GlyphView, val title: TextView, val subtitle: TextView,
        val closeButton: GlyphView) : ViewHolder(row) { var selected: Boolean? = null }
    private inner class Rows : ListAdapter<Row, Holder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(8), d(4), d(8)); minimumHeight = d(76)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, d(4), 0, d(4)) }
            }
            val handle = GlyphView(c, "bubble", "Drag tab to reorder", true)
            row.addView(handle, LinearLayout.LayoutParams(d(40), d(44)))
            val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(4), 0) }
            val title = Ui.text(c, "", 14f, Ui.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val subtitle = Ui.text(c, "", 11f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d(5), 0, 0) }
            text.addView(title); text.addView(subtitle); row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val close = GlyphView(c, "close", "Close conversation")
            row.addView(close, LinearLayout.LayoutParams(d(48), d(48))); return Holder(row, handle, title, subtitle, close)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            if (holder.selected != row.selected) { holder.selected = row.selected; holder.row.background = Ui.ripple(context, if (row.selected) Ui.SURFACE_HIGH else Ui.SURFACE, 20f) }
            if (holder.title.text != row.title) holder.title.text = row.title
            if (holder.subtitle.text != row.subtitle) holder.subtitle.text = row.subtitle
            val tint = if (row.unread || row.busy) Ui.MINT else Ui.MUTED
            if (holder.subtitle.currentTextColor != tint) holder.subtitle.setTextColor(tint)
            holder.row.contentDescription = "${row.title}, ${row.subtitle}${if (row.selected) ", selected" else ""}"
            holder.row.setOnClickListener { select(row.id) }
            holder.row.setOnLongClickListener {
                val ws = Workspace.peek() ?: return@setOnLongClickListener true
                val anchor = QuickPanel.hostAnchor(holder.row)
                TabResourceMenu.show(anchor, ws, row.id, select) {
                    if (options != null) options.invoke(holder.row, row.id)
                    else QuickMenus.tabOptions(anchor, ws, row.id, select)
                }
                true
            }
            holder.dragHandle.contentDescription = "Drag ${row.title} to reorder"
            holder.dragHandle.setOnClickListener(null)
            holder.dragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    drag.startDrag(holder)
                    true
                } else false
            }
            holder.closeButton.contentDescription = "Close ${row.title}"
            holder.closeButton.setOnClickListener {
                val ws = Workspace.peek()
                if (ws?.tabs?.any { it.id == row.id && (it.pinned || it.generating || Policy.isVoice(it.url)) } == true) {
                    QuickMenus.close(QuickPanel.hostAnchor(holder.row), ws, row.id)
                } else close(row.id)
            }
        }
    }
}
