package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

internal enum class PageAppearanceMode(val wire: String, val label: String) {
    DEFAULT("default", "Bubble default"),
    DARK("dark", "Force dark"),
    AMOLED("amoled", "AMOLED black"),
    LIGHT("light", "Force light");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

/**
 * Per-logical-tab webpage appearance. Bubble keeps its existing dark preferred-color-scheme as
 * the default. Explicit dark/AMOLED/light modes are implemented by the built-in content script while
 * the durable choice is keyed only by Bubble's UUID tab id. No site/account/page text is stored here.
 */
internal object PageAppearance {
    private const val PREFS = "bubble-page-appearance-v1"
    private const val NATIVE_APP = "bubbleAppearance"

    fun mode(context: Context, tabId: String): PageAppearanceMode =
        PageAppearanceMode.fromWire(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(tabId, null))

    fun label(context: Context, tabId: String): String = mode(context, tabId).label

    private fun set(context: Context, tabId: String, next: PageAppearanceMode) {
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (next == PageAppearanceMode.DEFAULT) edit.remove(tabId) else edit.putString(tabId, next.wire)
        edit.apply()
    }

    private fun response(context: Context, tabId: String) =
        JSONObject().put("mode", mode(context, tabId).wire)

    fun bind(context: Context, tabId: String, session: GeckoSession, addon: WebExtension) {
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                // This delegate is already scoped to this exact GeckoSession + built-in extension.
                // During document_start Gecko can transiently report sender metadata such as
                // isTopLevel/environmentType before it settles. Rejecting on those fields caused a
                // missed request to look like a successful DEFAULT response in the content script.
                // The content script itself is top-frame-only, so session identity is the durable
                // boundary here.
                if (nativeApp != NATIVE_APP || sender.session !== session) return null
                val request = message as? JSONObject ?: return null
                if (request.optString("event") != "appearance") return null
                return GeckoResult.fromValue(response(context, tabId))
            }

            override fun onConnect(port: WebExtension.Port) {
                // Connection-based fallback for devices/pages where a one-shot message races the
                // session delegate. The port is session-scoped by the same built-in extension.
                if (port.name != NATIVE_APP || port.sender.session !== session) {
                    port.disconnect(); return
                }
                runCatching { port.postMessage(response(context, tabId)) }
                port.disconnect()
            }
        }, NATIVE_APP)
        val profileId = Workspace.peek()?.tabs?.firstOrNull { it.id == tabId }?.profileId ?: ProfilePolicy.DEFAULT_ID
        ChatVaultBridge.bind(context, tabId, profileId, session, addon)
    }

    fun controls(anchor: View, workspace: Workspace, tabId: String) {
        val tab = workspace.tabs.firstOrNull { it.id == tabId } ?: return
        val panel = QuickPanel.open(anchor, workspace, "Page appearance · ${tab.displayName}", 380) ?: return
        fun d(n: Int) = Ui.dp(anchor.context, n.toFloat())
        val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), d(4), d(8), d(8)) }
        panel.body.addView(body, LinearLayout.LayoutParams(-1, -1))
        val current = mode(anchor.context, tabId)
        body.addView(Ui.text(anchor.context,
            "Current: ${current.label}. This affects only this Bubble tab and is restored with it.", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(10), d(6), d(10), d(8))
        }, LinearLayout.LayoutParams(-1, d(48)))

        fun row(value: PageAppearanceMode) = Ui.text(anchor.context,
            if (value == current) "✓ ${value.label}" else value.label, 14f,
            if (value == current) Ui.ACTIVE else Ui.ACCENT, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), 0, d(12), 0)
            isClickable = true; isFocusable = true; background = Ui.ripple(anchor.context, Color.TRANSPARENT, 14f)
            setOnClickListener {
                panel.finish {
                    val latest = workspace.tabs.firstOrNull { it.id == tabId } ?: return@finish
                    if (latest.generating) {
                        Toast.makeText(anchor.context, "Finish the active reply before changing this tab's page appearance.", Toast.LENGTH_LONG).show()
                        return@finish
                    }
                    set(anchor.context, tabId, value)
                    latest.session?.takeIf { it.isOpen }?.reload()
                    workspace.changed(true)
                    Toast.makeText(anchor.context, "${value.label} saved for this tab.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        PageAppearanceMode.entries.forEach { body.addView(row(it), LinearLayout.LayoutParams(-1, d(56))) }
        body.addView(Ui.text(anchor.context,
            "Dark keeps charcoal surfaces. AMOLED black uses true #000000 page/background surfaces while preserving readable controls. These modes are local presentation only and never change the website account setting.", 11f, Ui.MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(10), d(6), d(10), d(6))
        }, LinearLayout.LayoutParams(-1, d(64)))
    }
}
