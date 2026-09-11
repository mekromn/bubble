package com.mekromn.bubble

import android.os.SystemClock
import java.util.UUID
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Session-scoped native bridge for Google Voice.
 *
 * Voice notification content never leaves the device through this bridge. Native code sends only
 * the already-received notification identity (name/phone/message) to the exact resident Voice tab
 * so its content script can select the matching conversation when Google's own notificationclick
 * handler does not. The page can also return a verified conversation-row phone number to enrich the
 * Android notification.
 */
internal object VoicePageBridge {
    private const val NATIVE_APP = "bubbleVoice"
    private const val SERVICE_WORKER_TARGET_MS = 4_000L
    private val ports = HashMap<String, WebExtension.Port>()
    private val lookups = HashMap<String, (String?) -> Unit>()
    private var pendingServiceWorkerTabId: String? = null
    private var pendingServiceWorkerUntil = 0L

    fun bind(tabId: String, session: GeckoSession, addon: WebExtension) {
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                if (port.name != NATIVE_APP || sender.session !== session || !sender.isTopLevel ||
                    !Policy.isVoice(sender.url)) {
                    port.disconnect(); return
                }
                ports.put(tabId, port)?.takeIf { it !== port }?.let { runCatching { it.disconnect() } }
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, source: WebExtension.Port) {
                        if (ports[tabId] !== source || source.sender.session !== session) return
                        val value = message as? JSONObject ?: return
                        if (value.optString("event") != "lookup-result") return
                        val requestId = value.optString("requestId")
                        if (requestId.isBlank()) return
                        val phone = value.optString("phone").trim().takeIf { VoiceContactPolicy.telUri(it) != null }
                        lookups.remove(requestId)?.invoke(phone)
                    }

                    override fun onDisconnect(source: WebExtension.Port) {
                        if (ports[tabId] === source) ports.remove(tabId)
                    }
                })
            }
        }, NATIVE_APP)
    }

    fun routeNotification(tabId: String?, contact: VoiceContactInfo?, message: String) {
        val id = tabId ?: return
        post(id, JSONObject().apply {
            put("event", "open-notification")
            put("name", contact?.displayName.orEmpty().take(160))
            put("phone", contact?.phone.orEmpty().take(64))
            put("message", message.take(2048))
        })
    }

    fun lookupPhone(tabId: String?, contact: VoiceContactInfo?, message: String, result: (String?) -> Unit) {
        val id = tabId ?: run { result(null); return }
        val requestId = UUID.randomUUID().toString()
        lookups[requestId] = result
        val posted = post(id, JSONObject().apply {
            put("event", "lookup-notification")
            put("requestId", requestId)
            put("name", contact?.displayName.orEmpty().take(160))
            put("phone", contact?.phone.orEmpty().take(64))
            put("message", message.take(2048))
        })
        if (!posted) lookups.remove(requestId)?.invoke(null)
    }

    private fun post(tabId: String, payload: JSONObject): Boolean {
        val port = ports[tabId] ?: return false
        return runCatching { port.postMessage(payload); true }.getOrElse {
            if (ports[tabId] === port) ports.remove(tabId)
            false
        }
    }

    fun prepareNotificationClick(tabId: String?) {
        pendingServiceWorkerTabId = tabId
        pendingServiceWorkerUntil = SystemClock.uptimeMillis() + SERVICE_WORKER_TARGET_MS
    }

    fun clearNotificationClick(tabId: String?) {
        if (pendingServiceWorkerTabId == tabId) {
            pendingServiceWorkerTabId = null
            pendingServiceWorkerUntil = 0L
        }
    }

    fun installServiceWorker(runtime: GeckoRuntime, workspace: Workspace) {
        runtime.setServiceWorkerDelegate(object : GeckoRuntime.ServiceWorkerDelegate {
            override fun onOpenWindow(url: String): GeckoResult<GeckoSession> {
                if (!Policy.isWeb(url)) {
                    return GeckoResult.fromException(IllegalArgumentException("Unsupported service-worker URL"))
                }
                val pendingId = pendingServiceWorkerTabId.takeIf {
                    it != null && SystemClock.uptimeMillis() <= pendingServiceWorkerUntil
                }
                val existingVoice = if (Policy.isVoice(url)) {
                    pendingId?.let { id -> workspace.tabs.firstOrNull { it.id == id && Policy.isVoice(it.url) } }
                        ?: workspace.selected?.takeIf { Policy.isVoice(it.url) }
                        ?: workspace.tabs.firstOrNull { Policy.isVoice(it.url) }
                } else null
                val tab = existingVoice ?: workspace.create(url)
                if (existingVoice != null && workspace.selectedId != tab.id) workspace.select(tab.id)
                val session = workspace.ensureSession(tab)
                    ?: return GeckoResult.fromException(IllegalStateException("Could not open service-worker target session"))
                return GeckoResult.fromValue(session)
            }
        })
    }
}
