package com.mekromn.bubble

import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Explicit account container controls. Opening in another profile always creates a NEW tab. */
internal object ProfileMenus {
    fun show(anchor: View, ws: Workspace, choose: (String) -> Unit = ws::select, sourceId: String = ws.selectedId) {
        val sourceProfile = ws.tabs.firstOrNull { it.id == sourceId }?.profileId ?: ProfilePolicy.DEFAULT_ID
        val panel = QuickPanel.open(anchor, ws, "Profiles / accounts", 440) ?: return
        val rows = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(Ui.text(anchor.context,
            "Current tab: ${ws.profileName(sourceProfile)}\nTabs in the same profile share sign-ins. Different profiles keep separate site data.",
            13f, Ui.MUTED).apply { setPadding(dp(anchor, 12), dp(anchor, 10), dp(anchor, 12), dp(anchor, 12)) })
        rows.addView(entry(anchor, "＋ Create profile") { panel.finish { editor(anchor, ws, null, sourceId, choose) } })
        ws.profiles.toList().forEach { profile ->
            val count = ws.tabs.count { it.profileId == profile.id }
            val selected = sourceProfile == profile.id
            rows.addView(entry(anchor, "${if (selected) "✓  " else ""}${profile.name} · $count ${if (count == 1) "tab" else "tabs"}") {
                panel.finish { details(anchor, ws, profile.id, sourceId, choose) }
            }.apply { contentDescription = "Profile ${profile.name}" })
        }
        panel.body.addView(ScrollView(anchor.context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -1))
    }
    private fun details(anchor: View, ws: Workspace, profileId: String, sourceId: String, choose: (String) -> Unit) {
        val profile = ws.profiles.firstOrNull { it.id == profileId } ?: return
        val panel = QuickPanel.open(anchor, ws, "Profile · ${profile.name}", 290) ?: return
        val rows = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(entry(anchor, "New ChatGPT tab in this profile") { panel.finish { choose(ws.create(Policy.HOME, profileId).id) } })
        rows.addView(entry(anchor, "Open this address in this profile") {
            panel.finish { ws.openInProfile(sourceId, profileId)?.let { choose(it.id) } }
        }.apply { isEnabled = ws.tabs.any { it.id == sourceId } })
        rows.addView(entry(anchor, "Rename profile") { panel.finish { editor(anchor, ws, profile, sourceId, choose) } })
        rows.addView(Ui.text(anchor.context,
            "Your current tab stays open. Cookies and drafts are not copied between profiles. Sign in normally in the new tab.",
            12f, Ui.MUTED).apply { setPadding(dp(anchor, 12), dp(anchor, 8), dp(anchor, 12), dp(anchor, 8)) })
        panel.body.addView(ScrollView(anchor.context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -1))
    }
    private fun editor(anchor: View, ws: Workspace, profile: BrowserProfile?, sourceId: String, choose: (String) -> Unit) {
        val panel = QuickPanel.open(anchor, ws, if (profile == null) "Create isolated profile" else "Rename profile", 245) ?: return
        val name = EditText(anchor.context).apply {
            hint = "Profile name, e.g. Work"; contentDescription = "Profile name"
            setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(60)); textSize = 15f
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); background = Ui.shape(context, Ui.BG, 16f)
            setPadding(dp(anchor, 12), 0, dp(anchor, 12), 0); setText(profile?.name.orEmpty())
        }
        panel.body.addView(name, LinearLayout.LayoutParams(-1, dp(anchor, 48)))
        panel.body.addView(Ui.text(anchor.context,
            if (profile == null) "Creates fresh site storage and opens a new tab. The original tab is not changed."
            else "Only the label changes; your sign-ins stay in the same storage container.", 12f, Ui.MUTED).apply {
            setPadding(dp(anchor, 12), dp(anchor, 12), dp(anchor, 12), dp(anchor, 12))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.body.addView(entry(anchor, if (profile == null) "Create and open new tab" else "Save profile name") {
            val value = name.text.toString()
            val problem = ProfilePolicy.nameProblem(value, ws.profiles, profile?.id)
            if (problem != null) Toast.makeText(anchor.context, problem, Toast.LENGTH_SHORT).show()
            else {
                anchor.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.content.windowToken, 0)
                if (profile == null) {
                    val created = ws.createProfile(value)
                    panel.finish {
                        val tab = ws.openInProfile(sourceId, created.id) ?: ws.create(Policy.HOME, created.id)
                        choose(tab.id)
                    }
                } else {
                    ws.renameProfile(profile.id, value); panel.dismiss()
                }
            }
        })
        panel.onClose { anchor.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.content.windowToken, 0) }
    }
    private fun entry(anchor: View, text: String, click: () -> Unit) = Ui.text(anchor.context, text, 14f).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(anchor, 48); maxLines = 2
        setPadding(dp(anchor, 12), dp(anchor, 10), dp(anchor, 12), dp(anchor, 10))
        background = Ui.ripple(context, android.graphics.Color.TRANSPARENT, 12f)
        isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun dp(anchor: View, n: Int) = Ui.dp(anchor.context, n.toFloat())
}
