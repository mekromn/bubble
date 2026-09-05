package com.mekromn.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.mozilla.geckoview.GeckoSession

/** Local workspace conveniences. All targets are captured durable IDs; nothing auto-sends a
 * prompt, deletes a server conversation, or scrapes messages into local notes/templates. */
internal object QuickMenus {
    private data class Action(val title: String, val enabled: Boolean = true, val run: () -> Unit)
    fun navigation(anchor: View, ws: Workspace) {
        val tab = ws.selected ?: return
        val session = tab.session
        fun valid() = ws.selectedId == tab.id && tab in ws.tabs && tab.session === session
        actions(anchor, ws, "Page controls", listOf(
            Action("Forward", tab.forward) { if (valid()) session?.goForward() },
            Action("Stop loading", tab.loading) { if (valid()) ws.stopLoading(tab.id) },
            Action("Refresh") { if (valid()) {
                if (tab.generating) confirm(anchor, ws, "Refresh this generating chat?", "Reloading may interrupt the current response. Your conversation is not deleted.") { if (valid()) ws.retry() }
                else ws.retry()
            } },
            Action("Find in conversation / page") { if (valid()) find(anchor, ws) }
        ))
    }
    fun tabs(anchor: View, ws: Workspace, choose: (String) -> Unit = ws::select) {
        val panel = QuickPanel.open(anchor, ws, "Quick tabs", 480) ?: return
        val c = anchor.context
        val toolbar = LinearLayout(c)
        toolbar.addView(button(anchor, "＋ New chat") { panel.finish { choose(ws.create().id) } }, LinearLayout.LayoutParams(0, dp(anchor, 46), 1f))
        toolbar.addView(button(anchor, "Chat tools") { panel.finish { tools(anchor, ws, choose) } }, LinearLayout.LayoutParams(0, dp(anchor, 46), 1f))
        panel.body.addView(toolbar)
        val search = input(anchor, "Search open tabs", false, 120)
        panel.body.addView(search, LinearLayout.LayoutParams(-1, dp(anchor, 44)))
        val filters = LinearLayout(c)
        val result = Ui.text(c, "", 11f, Ui.MUTED).apply { setPadding(dp(anchor, 8), dp(anchor, 4), dp(anchor, 8), dp(anchor, 4)) }
        val list = ConversationList(c, { id -> panel.finish { choose(id) } }, { id -> panel.finish { close(anchor, ws, id) } },
            { row, id -> panel.finish { tabOptions(anchor, ws, id, choose) } })
        list.onResultCount = { result.text = if (it == 0) "No matching tabs" else "$it tabs · hold a row for options" }
        val chips = LinkedHashMap<TabFilter, TextView>()
        fun selectFilter(filter: TabFilter) {
            list.filter(filter)
            chips.forEach { (key, view) ->
                view.isSelected = key == filter
                view.setTextColor(if (key == filter) Ui.BLUE else Ui.MUTED)
                view.background = Ui.ripple(c, if (key == filter) Ui.SURFACE_HIGH else Ui.SURFACE, 14f)
            }
        }
        TabFilter.entries.forEach { filter ->
            val chip = button(anchor, filter.label) { selectFilter(filter) }
            chip.contentDescription = "Filter ${filter.label} tabs"; chips[filter] = chip
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(anchor, 44), 1f))
        }
        panel.body.addView(filters); panel.body.addView(result)
        panel.body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(watcher { list.search(it) })
        selectFilter(TabFilter.ALL)
        val update: () -> Unit = { if (panel.showing) list.refresh(ws) }
        ws.listen(update); panel.onClose { ws.unlisten(update) }
    }
    fun tools(anchor: View, ws: Workspace, choose: (String) -> Unit = ws::select) {
        val tab = ws.selected
        actions(anchor, ws, "Chat tools", listOf(
            Action("New ChatGPT chat") { choose(ws.create().id) },
            Action("Profiles / accounts · ${ws.profileName()}") { ProfileMenus.show(anchor, ws, choose) },
            Action("Next unread reply", ws.tabs.any { it.unread }) { ws.nextUnread()?.let { choose(it.id) } },
            Action("Prompt library · local") { prompts(anchor, ws) },
            Action("Find in conversation / page", tab?.session != null) { find(anchor, ws) },
            Action("Local notes for this tab", tab != null) { tab?.let { notes(anchor, ws, it.id) } },
            Action("Tab name, pin and reply alerts", tab != null) { tab?.let { tabOptions(anchor, ws, it.id, choose) } },
            Action("Duplicate current tab", tab != null) { tab?.let { ws.duplicate(it.id)?.let { copy -> choose(copy.id) } } },
            Action("Recently closed tabs", ws.closedTabs.isNotEmpty()) { recentlyClosed(anchor, ws, choose) },
            Action("Live-tab status") { status(anchor, ws) }
        ))
    }
    fun tabOptions(anchor: View, ws: Workspace, id: String, choose: (String) -> Unit = ws::select) {
        val tab = ws.tabs.firstOrNull { it.id == id } ?: return
        actions(anchor, ws, tab.displayName, listOf(
            Action(if (tab.pinned) "Unpin tab" else "Pin tab") { ws.togglePin(id) },
            Action("Rename locally") { edit(anchor, ws, "Local tab name", "Name (blank uses page title)", tab.localName, false, 120) { ws.rename(id, it) } },
            Action("Local notes") { notes(anchor, ws, id) },
            Action(if (tab.muted) "Enable reply alerts for this tab" else "Mute reply alerts for this tab") { ws.toggleMute(id) },
            Action("Profile · ${ws.profileName(tab.profileId)}") { ProfileMenus.show(anchor, ws, choose, id) },
            Action("Duplicate tab") { ws.duplicate(id)?.let { choose(it.id) } },
            Action("Copy conversation address") { copy(anchor, "Conversation address", tab.url) },
            Action("Close tab · not the conversation") { close(anchor, ws, id) }
        ))
    }
    fun close(anchor: View, ws: Workspace, id: String) {
        val tab = ws.tabs.firstOrNull { it.id == id } ?: return
        val finish = { ws.close(id); toast(anchor, "Tab closed. It is available in Recently closed tabs.") }
        if (tab.pinned || tab.generating) confirm(anchor, ws, "Close ${tab.displayName}?",
            if (tab.generating) "This tab is generating. Closing it stops its live browser session; it does not delete the ChatGPT conversation."
            else "This tab is pinned. Closing it does not delete the ChatGPT conversation.", finish)
        else finish()
    }
    private fun recentlyClosed(anchor: View, ws: Workspace, choose: (String) -> Unit) {
        val items = ws.closedTabs.toList()
        actions(anchor, ws, "Recently closed · last 20", items.map { tab ->
            Action(tab.localName.ifBlank { tab.title.ifBlank { Policy.host(tab.url) } }) { ws.reopen(tab.id)?.let { choose(it.id) } }
        } + Action("Clear closed-tab history", items.isNotEmpty()) {
            confirm(anchor, ws, "Clear recently closed tabs?", "This removes the local reopen history and notes for those closed tabs, not your open tabs or website history.") {
                ws.closedTabs.clear(); ws.changed(true)
            }
        })
    }
    private fun notes(anchor: View, ws: Workspace, id: String) {
        val tab = ws.tabs.firstOrNull { it.id == id } ?: return
        edit(anchor, ws, "Notes · only on this device", "Write your own notes. Nothing is sent to ChatGPT.", tab.note, true, 16384) {
            if (ws.tabs.any { t -> t.id == id }) ws.setNote(id, it) else toast(anchor, "That tab has been closed; the note was not changed.")
        }
    }
    private fun prompts(anchor: View, ws: Workspace) {
        actions(anchor, ws, "Prompt library · copy, never auto-send", listOf(Action("＋ Create a prompt") { promptEditor(anchor, ws, null) }) +
            ws.prompts.map { snippet -> Action(snippet.title) { prompt(anchor, ws, snippet.id) } })
    }
    private fun prompt(anchor: View, ws: Workspace, id: String) {
        val snippet = ws.prompts.firstOrNull { it.id == id } ?: return
        val panel = QuickPanel.open(anchor, ws, snippet.title, 410) ?: return
        val scroll = ScrollView(anchor.context)
        scroll.addView(Ui.text(anchor.context, snippet.body, 14f).apply { setPadding(dp(anchor, 12), dp(anchor, 10), dp(anchor, 12), dp(anchor, 16)); setTextIsSelectable(true) })
        panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(anchor.context)
        controls.addView(button(anchor, "Copy") { copy(anchor, snippet.title, snippet.body); panel.dismiss() }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        controls.addView(button(anchor, "Edit") { panel.finish { promptEditor(anchor, ws, snippet) } }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        controls.addView(button(anchor, "Delete") { panel.finish { confirm(anchor, ws, "Delete this saved prompt?", "This only removes your local template.") { ws.deletePrompt(id) } } }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        panel.body.addView(controls)
    }
    private fun promptEditor(anchor: View, ws: Workspace, snippet: PromptSnippet?) {
        val panel = QuickPanel.open(anchor, ws, if (snippet == null) "Create local prompt" else "Edit local prompt", 400) ?: return
        val title = input(anchor, "Prompt title", false, 120).apply { setText(snippet?.title.orEmpty()) }
        val body = input(anchor, "Prompt text · copied only when you ask", true, 16384).apply { setText(snippet?.body.orEmpty()) }
        panel.body.addView(title, LinearLayout.LayoutParams(-1, dp(anchor, 48)))
        panel.body.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.body.addView(button(anchor, "Save locally") {
            if (title.text.isBlank() || body.text.isBlank()) toast(anchor, "Give the prompt a title and some text.")
            else { ws.savePrompt(snippet?.id, title.text.toString(), body.text.toString()); hideInput(panel); panel.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(anchor, 48)))
        panel.onClose { hideInput(panel) }
    }
    private fun edit(anchor: View, ws: Workspace, title: String, hint: String, initial: String,
        multiline: Boolean, limit: Int, save: (String) -> Unit) {
        val panel = QuickPanel.open(anchor, ws, title, if (multiline) 360 else 210) ?: return
        val editor = input(anchor, hint, multiline, limit).apply { setText(initial); setSelection(text.length) }
        panel.body.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(anchor.context)
        buttons.addView(button(anchor, "Cancel") { hideInput(panel); panel.dismiss() }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        buttons.addView(button(anchor, "Save locally") { save(editor.text.toString()); hideInput(panel); panel.dismiss() }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        panel.body.addView(buttons); panel.onClose { hideInput(panel) }
    }
    fun find(anchor: View, ws: Workspace) {
        val tab = ws.selected ?: return
        val session = tab.session ?: return
        val url = tab.url
        val panel = QuickPanel.open(anchor, ws, "Find in conversation / page", 230) ?: return
        val query = input(anchor, "Find text", false, 512)
        query.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val result = Ui.text(anchor.context, "Searches this loaded page, not your whole ChatGPT account.", 12f, Ui.MUTED).apply {
            setPadding(dp(anchor, 8), dp(anchor, 8), dp(anchor, 8), dp(anchor, 4))
        }
        var generation = 0
        val handler = Handler(Looper.getMainLooper())
        val finder = session.finder
        val oldFlags = finder.displayFlags
        finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        fun search(backwards: Boolean, again: Boolean) {
            if (!panel.showing || ws.selectedId != tab.id || tab.session !== session || tab.url != url) return
            val text = query.text.toString()
            val token = ++generation
            if (text.isEmpty()) { finder.clear(); result.text = "Enter text to find"; return }
            finder.find(if (again) null else text, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD).accept({ found ->
                if (panel.showing && generation == token && found != null) {
                    result.text = if (!found.found) "No matches" else "${found.current} / ${if (found.total < 0) "…" else found.total}${if (found.wrapped) " · wrapped" else ""}"
                }
            }, { if (panel.showing && generation == token) result.text = "Search unavailable for this page" })
        }
        val update = Runnable { search(false, false) }
        query.addTextChangedListener(watcher { handler.removeCallbacks(update); handler.postDelayed(update, 160) })
        query.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { handler.removeCallbacks(update); search(false, false); true } else false }
        panel.body.addView(query, LinearLayout.LayoutParams(-1, dp(anchor, 48)))
        panel.body.addView(result, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(anchor.context)
        controls.addView(button(anchor, "Previous match") { handler.removeCallbacks(update); search(true, true) }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        controls.addView(button(anchor, "Next match") { handler.removeCallbacks(update); search(false, true) }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        panel.body.addView(controls)
        val watch: () -> Unit = { if (tab !in ws.tabs || tab.session !== session || ws.selectedId != tab.id || tab.url != url) panel.dismiss() }
        ws.listen(watch)
        panel.onClose {
            generation++; handler.removeCallbacksAndMessages(null); ws.unlisten(watch)
            if (session.isOpen) { finder.clear(); finder.setDisplayFlags(oldFlags) }
            hideInput(panel)
        }
    }
    private fun status(anchor: View, ws: Workspace) {
        val panel = QuickPanel.open(anchor, ws, "Live-tab behavior", 340) ?: return
        val text = "${ws.tabs.count { it.session?.isOpen == true }} / ${ws.tabs.size} open sessions resident\nAll resident tabs: active + high priority\nPage foreground compatibility: ${if (ws.liveCompatibilityReady) "registered for web pages and frames" else "unavailable / starting"}\n\nPages report visible and focused. Real keyboard focus stays with the selected view. This does not create trusted user gestures, override Android sleep/force-stop, or guarantee a website cannot detect the compatibility script. Keeping many tabs live uses more memory and battery."
        panel.body.addView(ScrollView(anchor.context).apply { addView(Ui.text(anchor.context, text, 13f, Ui.MUTED).apply { setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 12)) }) }, LinearLayout.LayoutParams(-1, -1))
    }
    private fun confirm(anchor: View, ws: Workspace, title: String, message: String, confirmed: () -> Unit) {
        val panel = QuickPanel.open(anchor, ws, title, 260) ?: return
        panel.body.addView(ScrollView(anchor.context).apply { addView(Ui.text(anchor.context, message, 14f).apply { setPadding(dp(anchor, 10), dp(anchor, 10), dp(anchor, 10), dp(anchor, 10)) }) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(anchor.context)
        row.addView(button(anchor, "Cancel") { panel.dismiss() }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        row.addView(button(anchor, "Confirm") { panel.finish(confirmed) }, LinearLayout.LayoutParams(0, dp(anchor, 48), 1f))
        panel.body.addView(row)
    }
    private fun actions(anchor: View, ws: Workspace, title: String, actions: List<Action>) {
        val panel = QuickPanel.open(anchor, ws, title, minOf(520, 64 + actions.size * 50)) ?: return
        val rows = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { action ->
            rows.addView(button(anchor, action.title) { panel.finish(action.run) }.apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 8))
                isEnabled = action.enabled; alpha = if (action.enabled) 1f else .4f; maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(anchor, 50)))
        }
        panel.body.addView(ScrollView(anchor.context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -1))
    }
    private fun button(anchor: View, value: String, click: () -> Unit) = Ui.text(anchor.context, value, 13f, Ui.BLUE, true).apply {
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        background = Ui.ripple(context, Ui.SURFACE, 16f); setOnClickListener { click() }
    }
    private fun input(anchor: View, hint: String, multiline: Boolean, limit: Int) = EditText(anchor.context).apply {
        this.hint = hint; contentDescription = hint; textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        background = Ui.shape(context, Ui.BG, 16f); setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 8))
        setSingleLine(!multiline); if (multiline) gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(InputFilter.LengthFilter(limit))
    }
    private fun watcher(update: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private fun hideInput(panel: QuickPanel) { panel.anchor.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.content.windowToken, 0) }
    private fun copy(anchor: View, label: String, value: String) {
        anchor.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        toast(anchor, "Copied. Paste it where you choose; nothing was sent.")
    }
    private fun toast(anchor: View, value: String) { Toast.makeText(anchor.context, value, Toast.LENGTH_SHORT).show() }
    private fun dp(anchor: View, n: Int) = Ui.dp(anchor.context, n.toFloat())
}
