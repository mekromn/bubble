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

/**
 * Exact-origin Gecko native-message boundary for the local Continuity Vault.
 *
 * It deliberately uses a separate native-app name from reply lifecycle messages. Transcript text is
 * accepted only from the built-in extension, the exact GeckoSession, top-level ChatGPT content
 * script, exact trusted origin, and the native Bubble profile that owns the tab. No generic webpage
 * can choose or cross that profile boundary.
 *
 * ChatGPT virtualizes long conversations and often re-renders only a short tail after refresh. A
 * rendered-tail snapshot must therefore never replace a larger local snapshot that Bubble already
 * captured. Full-history captures or equal/larger snapshots may still update the entry normally.
 */
internal object ChatVaultBridge {
    private const val NATIVE_APP = "bubbleVault"

    fun bind(context: Context, tabId: String, profileId: String, session: GeckoSession, addon: WebExtension) {
        val vault = ChatVaultRegistry.get(context)
        val ignoredTransfers = HashSet<String>()
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
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
                        if (transfer.isNotBlank() && incomingCount in 0 until savedCount) {
                            ignoredTransfers += transfer
                        } else {
                            vault.begin(tabId, profileId, payload)
                        }
                        null
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
                    "vault-pending-request" -> GeckoResult.fromValue(
                        vault.pendingResponse(profileId) ?: JSONObject().put("pending", false)
                    )
                    "vault-pending-consumed" -> {
                        vault.clearPending(payload.optString("sourceId").takeIf { it.isNotBlank() })
                        null
                    }
                    "vault-handoff-loaded" -> {
                        Toast.makeText(context.applicationContext,
                            if (payload.optBoolean("complete")) "Previous chat loaded locally · press Send when ready"
                            else "Size-aware previous-chat handoff loaded · press Send when ready",
                            Toast.LENGTH_LONG).show()
                        null
                    }
                    "vault-agent-go-loaded" -> {
                        Toast.makeText(context.applicationContext,
                            "Assistant requested another turn · go is loaded; press Send when ready",
                            Toast.LENGTH_LONG).show()
                        null
                    }
                    else -> null
                }
            }
        }, NATIVE_APP)
    }
}
