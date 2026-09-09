package com.mekromn.bubble

import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** One process-local owner for the app-private vault. Disk IO remains inside ChatVault. */
internal object ChatVaultRegistry {
    @Volatile private var instance: ChatVault? = null
    fun get(context: Context): ChatVault {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return ChatVault(context.applicationContext) {}.also { instance = it }
        }
    }
}

/** Exact-origin Gecko native-message boundary for the local Continuity Vault. */
internal object ChatVaultBridge {
    private const val NATIVE_APP = "bubbleVault"
    private const val TRANSCRIPT_CHUNK_BYTES = 32 * 1024
    private data class ActiveExport(val export: ChatTranscriptExport, val fingerprint: String)

    fun bind(context: Context, tabId: String, profileId: String, session: GeckoSession, addon: WebExtension) {
        val vault = ChatVaultRegistry.get(context)
        val ignoredTransfers = HashSet<String>()
        val transcriptExports = LinkedHashMap<String, ActiveExport>()
        val activeTranscriptFingerprints = HashSet<String>()
        ChatTranscriptExports.initialize(context)

        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                if (port.name != NATIVE_APP || sender.session !== session || !sender.isTopLevel ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !Policy.isChat(sender.url)) {
                    port.disconnect(); return
                }
                ChatVaultControls.bind(context, vault, tabId, profileId, session, port)
            }

            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                if (nativeApp != NATIVE_APP || sender.session !== session || !sender.isTopLevel ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !Policy.isChat(sender.url)) return null
                val payload = message as? JSONObject ?: return null
                return when (payload.optString("event")) {
                    "vault-snapshot-begin" -> {
                        val transfer = payload.optString("transfer")
                        val chatId = payload.optString("chatId")
                        val incomingCount = payload.optInt("messages", -1)
                        val savedCount = vault.summaries().firstOrNull {
                            it.id == chatId && it.profileId == profileId
                        }?.messages ?: 0
                        val accepted = transfer.isNotBlank() && VaultSnapshotPolicy.accepts(vault.loaded, savedCount, incomingCount)
                        if (!accepted) ignoredTransfers += transfer else vault.begin(tabId, profileId, payload)
                        GeckoResult.fromValue(JSONObject().put("accepted", accepted))
                    }
                    "vault-snapshot-chunk" -> {
                        val transfer = payload.optString("transfer")
                        if (transfer !in ignoredTransfers) vault.chunk(tabId, payload)
                        null
                    }
                    "vault-snapshot-end" -> {
                        val transfer = payload.optString("transfer")
                        if (!ignoredTransfers.remove(transfer)) vault.end(tabId, payload)
                        null
                    }
                    "vault-stage" -> {
                        payload.optString("chatId").takeIf { it.isNotBlank() }?.let { vault.stage(it, profileId) }
                        null
                    }
                    "vault-pending-request" -> GeckoResult.fromValue(vault.pendingResponse(profileId) ?: JSONObject().put("pending", false))
                    "vault-pending-consumed" -> { vault.clearPending(payload.optString("sourceId").takeIf { it.isNotBlank() }); null }
                    "vault-handoff-loaded" -> {
                        Toast.makeText(context.applicationContext,
                            if (payload.optBoolean("complete")) "Previous chat loaded locally · press Send when ready"
                            else "Size-aware previous-chat handoff loaded · press Send when ready", Toast.LENGTH_LONG).show(); null
                    }
                    "vault-agent-go-loaded" -> {
                        Toast.makeText(context.applicationContext, "Assistant requested another turn · go is loaded; press Send when ready", Toast.LENGTH_LONG).show(); null
                    }
                    "vault-transcript-begin" -> {
                        val fingerprint = payload.optString("fingerprint").takeIf { it.length in 8..512 }
                            ?: return GeckoResult.fromValue(JSONObject().put("available", false).put("reason", "bad-fingerprint"))
                        if (ChatTranscriptCommandState.completed(context, tabId) == fingerprint || !activeTranscriptFingerprints.add(fingerprint)) {
                            return GeckoResult.fromValue(JSONObject().put("available", false).put("duplicate", true))
                        }
                        val result = GeckoResult<Any>()
                        ChatTranscriptExports.begin(context, vault, sender.url, profileId) { export ->
                            if (export == null) {
                                activeTranscriptFingerprints.remove(fingerprint)
                                result.complete(JSONObject().put("available", false).put("reason", "vault-unavailable"))
                            } else {
                                transcriptExports[export.transfer] = ActiveExport(export, fingerprint)
                                result.complete(JSONObject().put("available", true).put("transfer", export.transfer)
                                    .put("sourceId", export.sourceId).put("filename", export.filename)
                                    .put("bytes", export.bytes).put("chunkBytes", TRANSCRIPT_CHUNK_BYTES))
                            }
                        }
                        result
                    }
                    "vault-transcript-chunk" -> {
                        val transfer = payload.optString("transfer")
                        val offset = payload.optLong("offset", -1L)
                        val active = transcriptExports[transfer] ?: return GeckoResult.fromValue(JSONObject().put("ok", false))
                        val result = GeckoResult<Any>()
                        ChatTranscriptExports.chunk(active.export, offset, TRANSCRIPT_CHUNK_BYTES) { chunk ->
                            result.complete(if (chunk == null) JSONObject().put("ok", false) else JSONObject()
                                .put("ok", true).put("data", chunk.base64).put("next", chunk.next).put("done", chunk.done))
                        }
                        result
                    }
                    "vault-transcript-complete" -> {
                        val active = transcriptExports.remove(payload.optString("transfer"))
                        if (active != null) {
                            activeTranscriptFingerprints.remove(active.fingerprint)
                            ChatTranscriptCommandState.markCompleted(context, tabId, active.fingerprint)
                            ChatTranscriptExports.discard(active.export)
                        }
                        null
                    }
                    "vault-transcript-cancel" -> {
                        val active = transcriptExports.remove(payload.optString("transfer"))
                        if (active != null) {
                            activeTranscriptFingerprints.remove(active.fingerprint)
                            ChatTranscriptExports.discard(active.export)
                        }
                        null
                    }
                    else -> null
                }
            }
        }, NATIVE_APP)
    }
}
