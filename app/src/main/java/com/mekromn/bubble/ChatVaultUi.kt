package com.mekromn.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Native UI for the app-private, profile-scoped ChatGPT Continuity Vault. */
internal object ChatVaultUi {
    fun show(anchor: View, workspace: Workspace, choose: (String) -> Unit = workspace::select) {
        val panel = QuickPanel.open(anchor, workspace, "Continuity Vault · local", 560) ?: return
        val c = anchor.context
        val vault = ChatVaultRegistry.get(c)
        val search = EditText(c).apply {
            hint = "Search saved chats"; contentDescription = "Search saved chats"; setSingleLine(true)
            textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            setPadding(d(anchor, 14), 0, d(anchor, 14), 0); background = Ui.shape(c, Ui.BG, 17f, Ui.LINE)
        }
        panel.body.addView(search, LinearLayout.LayoutParams(-1, d(anchor, 46)))
        val status = Ui.text(c, "", 11f, Ui.MUTED).apply { setPadding(d(anchor, 8), d(anchor, 5), d(anchor, 8), d(anchor, 5)) }
        panel.body.addView(status)
        var archiving = false
        panel.body.addView(action(anchor, "Archive all open chats now") {
            if (archiving) return@action
            archiving = true
            status.text = "Full-syncing every open ChatGPT tab into the local Vault…"
            ChatTabMaintenance.archiveAll(workspace) { result ->
                archiving = false
                if (!panel.showing) return@archiveAll
                renderVaultStatus(status, vault, workspace, search.text.toString())
                val summary = when {
                    result.total == 0 -> "No open ChatGPT chats to archive."
                    result.failed == 0 -> "Archived all ${result.archived} open chats · ${result.messages} messages in the Vault."
                    else -> "Archived ${result.archived}/${result.total} open chats · ${result.messages} messages. ${result.failed} could not be full-synced."
                }
                toast(anchor, summary)
            }
        })
        val rows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(c).apply { addView(rows, LinearLayout.LayoutParams(-1, -2)) }
        panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun render() {
            if (!panel.showing) return
            val query = search.text.toString().trim().lowercase()
            val items = vault.summaries().filter { item ->
                val profile = workspace.profileName(item.profileId)
                query.isBlank() || item.title.lowercase().contains(query) || item.url.lowercase().contains(query) || profile.lowercase().contains(query)
            }
            if (!archiving) renderVaultStatus(status, vault, workspace, query)
            rows.removeAllViews()
            items.forEach { summary ->
                val relative = DateUtils.getRelativeTimeSpanString(summary.updatedAt, System.currentTimeMillis(), 60_000).toString()
                val profile = workspace.profileName(summary.profileId)
                rows.addView(Ui.text(c, "${summary.title}\n$profile · ${summary.messages} messages · $relative", 13f, Ui.TEXT, true).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(d(anchor, 12), d(anchor, 8), d(anchor, 12), d(anchor, 8))
                    isClickable = true; isFocusable = true; background = Ui.ripple(c, Ui.SURFACE, 16f)
                    setOnClickListener { panel.finish { entry(anchor, workspace, summary.id, choose) } }
                }, LinearLayout.LayoutParams(-1, d(anchor, 64)).apply { setMargins(0, d(anchor, 2), 0, d(anchor, 2)) })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
        if (!vault.loaded) {
            val handler = Handler(Looper.getMainLooper())
            val refresh = object : Runnable {
                override fun run() {
                    if (!panel.showing) return
                    render()
                    if (!vault.loaded) handler.postDelayed(this, 120)
                }
            }
            handler.postDelayed(refresh, 120)
            panel.onClose { handler.removeCallbacks(refresh) }
        }
    }

    private fun renderVaultStatus(status: android.widget.TextView, vault: ChatVault, workspace: Workspace, rawQuery: String) {
        val query = rawQuery.trim().lowercase()
        val items = vault.summaries().filter { item ->
            val profile = workspace.profileName(item.profileId)
            query.isBlank() || item.title.lowercase().contains(query) || item.url.lowercase().contains(query) || profile.lowercase().contains(query)
        }
        status.text = when {
            !vault.loaded -> "Loading the local vault…"
            items.isEmpty() && query.isNotBlank() -> "No saved chats match this search"
            items.isEmpty() -> "No saved ChatGPT chats yet · full history auto-syncs locally"
            else -> "${items.size} saved chat${if (items.size == 1) "" else "s"} · profile-isolated · cumulative full history · no automatic pruning"
        }
    }

    private fun entry(anchor: View, workspace: Workspace, id: String, choose: (String) -> Unit) {
        val panel = QuickPanel.open(anchor, workspace, "Saved chat", 560) ?: return
        val c = anchor.context
        val vault = ChatVaultRegistry.get(c)
        val title = Ui.text(c, "Loading…", 16f, Ui.TEXT, true).apply { setPadding(d(anchor, 8), d(anchor, 6), d(anchor, 8), d(anchor, 8)) }
        panel.body.addView(title)
        val preview = Ui.text(c, "", 12f, Ui.TEXT).apply {
            setPadding(d(anchor, 10), d(anchor, 8), d(anchor, 10), d(anchor, 12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(c).apply { addView(preview, LinearLayout.LayoutParams(-1, -2)) }
        panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        panel.body.addView(controls, LinearLayout.LayoutParams(-1, -2))

        vault.read(id) { chat ->
            if (!panel.showing) return@read
            if (chat == null) {
                title.text = "Saved chat unavailable"
                preview.text = "The local snapshot could not be read. The live ChatGPT conversation was not modified."
                return@read
            }
            title.text = "${chat.title} · ${workspace.profileName(chat.profileId)} · ${chat.messages.size} messages"
            preview.text = buildString {
                chat.messages.forEachIndexed { index, message ->
                    append(if (message.role == "user") "USER" else "ASSISTANT")
                    append("\n").append(message.text)
                    if (index != chat.messages.lastIndex) append("\n\n")
                }
            }
            controls.removeAllViews()
            controls.addView(action(anchor, "Continue in a new ChatGPT chat") {
                // stage() and handoff() share the Vault's serialized IO lane. handoff() acts as the
                // completion barrier here; the fresh page then requests the staged full .md export.
                vault.stage(id, chat.profileId)
                vault.handoff(id) { handoff ->
                    if (handoff == null) toast(anchor, "Could not stage the local continuity chat.")
                    else {
                        panel.dismiss()
                        val tab = workspace.create(Policy.HOME, chat.profileId)
                        choose(tab.id)
                        toast(anchor, "Previous chat staged in ${workspace.profileName(chat.profileId)}. Its full transcript will attach in the new composer; press Send when ready.")
                    }
                }
            })
            controls.addView(action(anchor, "Copy continuity handoff") {
                vault.handoff(id) { handoff ->
                    if (handoff == null) toast(anchor, "Could not build the handoff.")
                    else { copy(c, "Continuity handoff", handoff.text); toast(anchor, "Continuity handoff copied") }
                }
            })
            controls.addView(action(anchor, "Open saved conversation in another tab") {
                panel.dismiss(); choose(workspace.create(chat.url, chat.profileId).id)
            })
            controls.addView(action(anchor, "Delete this local saved copy") {
                vault.delete(id); panel.dismiss(); toast(anchor, "Local Vault copy deleted. The server conversation was not deleted.")
            })
        }
    }

    private fun action(anchor: View, label: String, run: () -> Unit) = Ui.text(anchor.context, label, 13f, Ui.ACCENT, true).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(d(anchor, 12), 0, d(anchor, 12), 0)
        isClickable = true; isFocusable = true; background = Ui.ripple(anchor.context, Ui.SURFACE, 14f)
        setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(-1, d(anchor, 46)).apply { setMargins(0, d(anchor, 2), 0, d(anchor, 2)) }
    }

    private fun copy(context: android.content.Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
    private fun toast(anchor: View, text: String) = Toast.makeText(anchor.context, text, Toast.LENGTH_LONG).show()
    private fun d(anchor: View, n: Int) = Ui.dp(anchor.context, n.toFloat())
}
