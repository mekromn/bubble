package com.mekromn.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

internal data class ChatArchiveResult(val success: Boolean, val messages: Int = 0, val reason: String = "")
internal data class ChatHeartbeatResult(val success: Boolean, val fingerprint: String = "", val busy: Boolean = false, val reason: String = "")

/**
 * App-to-content-script control plane for an exact running ChatGPT GeckoSession.
 *
 * One native Port belongs to one logical Bubble tab/session. It lets explicit native actions request
 * a same-origin full-history archive or return a lightweight local activity fingerprint without
 * selecting/focusing the tab, scrolling it, or fabricating user input. All callbacks are main-thread
 * and bounded by a timeout.
 */
internal object ChatVaultControls {
    private data class Channel(
        val app: Context,
        val vault: ChatVault,
        val tabId: String,
        val profileId: String,
        val session: GeckoSession,
        val port: WebExtension.Port
    )
    private enum class Kind { ARCHIVE, HEARTBEAT }
    private data class Pending(
        val kind: Kind,
        val tabId: String,
        val profileId: String,
        val archive: ((ChatArchiveResult) -> Unit)?,
        val heartbeat: ((ChatHeartbeatResult) -> Unit)?,
        val timeout: Runnable
    )

    private val main = Handler(Looper.getMainLooper())
    private val channels = LinkedHashMap<String, Channel>()
    private val pending = LinkedHashMap<String, Pending>()

    fun bind(context: Context, vault: ChatVault, tabId: String, profileId: String,
        session: GeckoSession, port: WebExtension.Port) {
        check(Looper.myLooper() == Looper.getMainLooper())
        channels.remove(tabId)?.port?.takeIf { it !== port }?.disconnect()
        val channel = Channel(context.applicationContext, vault, tabId, profileId, session, port)
        channels[tabId] = channel
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, source: WebExtension.Port) {
                if (source !== port || channels[tabId]?.port !== port) return
                val value = message as? JSONObject ?: return
                val requestId = value.optString("requestId")
                val request = pending.remove(requestId) ?: return
                if (request.tabId != tabId || request.profileId != profileId) return
                main.removeCallbacks(request.timeout)
                when (request.kind) {
                    Kind.HEARTBEAT -> request.heartbeat?.invoke(ChatHeartbeatResult(
                        success = value.optString("event") == "heartbeat-result" && value.optBoolean("ok"),
                        fingerprint = value.optString("fingerprint").take(512),
                        busy = value.optBoolean("busy"),
                        reason = value.optString("reason")
                    ))
                    Kind.ARCHIVE -> {
                        if (value.optString("event") != "archive-full-history-result" || !value.optBoolean("ok")) {
                            request.archive?.invoke(ChatArchiveResult(false, reason = value.optString("reason").ifBlank { "No full-history snapshot was available" }))
                            return
                        }
                        val chatId = value.optString("chatId")
                        if (chatId.isBlank()) {
                            request.archive?.invoke(ChatArchiveResult(false, reason = "The page returned no conversation id"))
                            return
                        }
                        // Page sends this result only after snapshot-end. read() joins ChatVault's
                        // serialized IO lane behind that commit, so message count is durable here.
                        channel.vault.read(chatId) { chat ->
                            if (chat == null || chat.profileId != profileId) request.archive?.invoke(ChatArchiveResult(false, reason = "Vault commit unavailable"))
                            else request.archive?.invoke(ChatArchiveResult(true, chat.messages.size))
                        }
                    }
                }
            }

            override fun onDisconnect(source: WebExtension.Port) {
                if (channels[tabId]?.port === source) channels.remove(tabId)
                failForTab(tabId, "Chat tab disconnected")
                ChatTabMaintenance.channelClosed(tabId)
            }
        })
    }

    fun unbind(tabId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        channels.remove(tabId)?.port?.disconnect()
        failForTab(tabId, "Chat tab is not running")
        ChatTabMaintenance.channelClosed(tabId)
    }

    fun archive(tabId: String, callback: (ChatArchiveResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val channel = channels[tabId] ?: run { callback(ChatArchiveResult(false, reason = "Chat archive bridge is not ready")); return }
        val requestId = UUID.randomUUID().toString()
        val timeout = Runnable {
            val request = pending.remove(requestId) ?: return@Runnable
            request.archive?.invoke(ChatArchiveResult(false, reason = "Full-history archive timed out"))
        }
        pending[requestId] = Pending(Kind.ARCHIVE, tabId, channel.profileId, callback, null, timeout)
        main.postDelayed(timeout, 45_000L)
        send(channel, requestId, "archive-full-history", timeout, callback)
    }

    fun heartbeat(tabId: String, callback: (ChatHeartbeatResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val channel = channels[tabId] ?: run { callback(ChatHeartbeatResult(false, reason = "Heartbeat bridge is not ready")); return }
        val requestId = UUID.randomUUID().toString()
        val timeout = Runnable {
            val request = pending.remove(requestId) ?: return@Runnable
            request.heartbeat?.invoke(ChatHeartbeatResult(false, reason = "Heartbeat timed out"))
        }
        pending[requestId] = Pending(Kind.HEARTBEAT, tabId, channel.profileId, null, callback, timeout)
        main.postDelayed(timeout, 12_000L)
        try {
            channel.port.postMessage(JSONObject().put("event", "heartbeat").put("requestId", requestId))
        } catch (_: RuntimeException) {
            main.removeCallbacks(timeout); pending.remove(requestId)
            callback(ChatHeartbeatResult(false, reason = "Could not ping this chat tab"))
        }
    }

    fun ready(tabId: String): Boolean = channels.containsKey(tabId)

    private fun send(channel: Channel, requestId: String, event: String, timeout: Runnable,
        callback: (ChatArchiveResult) -> Unit) {
        try { channel.port.postMessage(JSONObject().put("event", event).put("requestId", requestId)) }
        catch (_: RuntimeException) {
            main.removeCallbacks(timeout); pending.remove(requestId)
            callback(ChatArchiveResult(false, reason = "Could not contact this chat tab"))
        }
    }

    private fun failForTab(tabId: String, reason: String) {
        val ids = pending.filterValues { it.tabId == tabId }.keys.toList()
        ids.forEach { id ->
            val request = pending.remove(id) ?: return@forEach
            main.removeCallbacks(request.timeout)
            request.archive?.invoke(ChatArchiveResult(false, reason = reason))
            request.heartbeat?.invoke(ChatHeartbeatResult(false, reason = reason))
        }
    }
}
