package com.mekromn.bubble

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
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
        val selected: Boolean, val unread: Boolean, val busy: Boolean, val pinned: Boolean,
        val readiness: TabReadiness)
    private var rows = emptyList<Row>(); private var query = ""; private var filter = TabFilter.ALL
    private var pendingReveal: String? = null
    private var lastSelectedForReveal = ""
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
        if (lastSelectedForReveal != workspace.selectedId) {
            lastSelectedForReveal = workspace.selectedId
            pendingReveal = workspace.selectedId
        }
        val next = workspace.tabs.sortedByDescending { it.pinned }.map { tab ->
            val selected = tab.id == workspace.selectedId
            val status = TabStatusPolicy.of(tab, selected && workspace.chatVisible)
            val prefix = buildString {
                if (workspace.profiles.size > 1) append(workspace.profileName(tab.profileId)).append(" · ")
                if (tab.pinned) append("Pinned · ")
            }
            val suffix = if (tab.muted) " · alerts muted" else ""
            Row(tab.id, tab.displayName, prefix + status.detail + suffix,
                selected, tab.unread, status.busy, tab.pinned, status.readiness)
        }
        if (rows == next) { revealPending(); return }
        rows = next; submit()
    }
    /** Reveal the tab the user came from when opening the switcher, instead of jumping to the top. */
    fun reveal(id: String) { pendingReveal = id; revealPending() }
    fun search(value: String) { query = value.trim(); submit() }
    fun filter(value: TabFilter) { filter = value; submit() }
    private fun submit() {
        val matched = rows.filter { QuickTabPolicy.accepts(filter, it.unread, it.busy, it.pinned) && (it.title.contains(query, true) || it.subtitle.contains(query, true)) }
        cards.submitList(matched) { revealPending() }; onResultCount?.invoke(matched.size)
    }
    private fun revealPending() {
        val id = pendingReveal ?: return
        val position = cards.currentList.indexOfFirst { it.id == id }
        if (position < 0) return
        pendingReveal = null
        post {
            val manager = layoutManager as? LinearLayoutManager ?: return@post
            val offset = ((height - d(76)) / 3).coerceAtLeast(0)
            manager.scrollToPositionWithOffset(position, offset)
        }
    }
    private fun d(value: Int) = Ui.dp(context, value.toFloat())
    private fun fill(readiness: TabReadiness, selected: Boolean): Int {
        val base = readiness.fill
        // Selection needs to be immediately obvious even in peripheral vision. Keep ordinary tabs
        // subtle, but make the active tab a dense version of its current semantic status color.
        return Color.argb(if (selected) 0xd4 else 0x38, Color.red(base), Color.green(base), Color.blue(base))
    }
    private fun edge(readiness: TabReadiness, selected: Boolean): Int = if (selected) readiness.edge else
        Color.argb(0x78, Color.red(readiness.edge), Color.green(readiness.edge), Color.blue(readiness.edge))

    private inner class Holder(val row: LinearLayout, val activeMark: View, val dragHandle: GlyphView,
        val title: TextView, val statusDot: View, val subtitle: TextView, val closeButton: GlyphView) : ViewHolder(row) {
        var selected: Boolean? = null
        var readiness: TabReadiness? = null
    }
    private inner class Rows : ListAdapter<Row, Holder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(d(5), d(8), d(4), d(8)); minimumHeight = d(76)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, d(4), 0, d(4)) }
            }
            val active = View(c).apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(active, LinearLayout.LayoutParams(d(5), d(46)).apply { marginEnd = d(5) })
            val handle = GlyphView(c, "bubble", "Drag tab to reorder", true)
            row.addView(handle, LinearLayout.LayoutParams(d(40), d(44)))
            val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(4), 0) }
            val title = Ui.text(c, "", 14f, Ui.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val statusLine = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(5), 0, 0) }
            val dot = View(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val subtitle = Ui.text(c, "", 11f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            statusLine.addView(dot, LinearLayout.LayoutParams(d(8), d(8)).apply { marginEnd = d(7) })
            statusLine.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
            text.addView(title); text.addView(statusLine); row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val close = GlyphView(c, "close", "Close conversation")
            row.addView(close, LinearLayout.LayoutParams(d(48), d(48)))
            return Holder(row, active, handle, title, dot, subtitle, close)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            if (holder.selected != row.selected || holder.readiness != row.readiness) {
                holder.selected = row.selected; holder.readiness = row.readiness
                val stroke = edge(row.readiness, row.selected)
                val content = Ui.shape(context, fill(row.readiness, row.selected), 20f, stroke).apply {
                    setStroke(d(if (row.selected) 2 else 1), stroke)
                }
                holder.row.background = RippleDrawable(ColorStateList.valueOf(GlassPalette.RIPPLE), content, null)
                holder.activeMark.visibility = if (row.selected) View.VISIBLE else View.INVISIBLE
                holder.activeMark.background = Ui.shape(context, row.readiness.edge, 3f)
                holder.statusDot.background = Ui.shape(context, row.readiness.edge, 4f)
                holder.row.elevation = if (row.selected) d(3).toFloat() else 0f
            }
            if (holder.title.text != row.title) holder.title.text = row.title
            if (holder.subtitle.text != row.subtitle) holder.subtitle.text = row.subtitle
            if (holder.subtitle.currentTextColor != row.readiness.edge) holder.subtitle.setTextColor(row.readiness.edge)
            holder.row.contentDescription = "${row.title}, ${row.readiness.label}, ${row.subtitle}${if (row.selected) ", selected" else ""}"
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
