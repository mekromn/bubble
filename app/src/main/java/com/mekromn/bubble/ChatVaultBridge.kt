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
 * script, and exact trusted origin. No generic webpage can reach this storage path.
 */
internal object ChatVaultBridge {
    private const val NATIVE_APP = "bubbleVault"

    fun bind(context: Context, tabId: String, session: GeckoSession, addon: WebExtension) {
        val vault = ChatVaultRegistry.get(context)
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                if (nativeApp != NATIVE_APP || sender.session !== session || !sender.isTopLevel ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !Policy.isChat(sender.url)) return null
                val payload = message as? JSONObject ?: return null
                return when (payload.optString("event")) {
                    "vault-snapshot-begin" -> { vault.begin(tabId, payload); null }
                    "vault-snapshot-chunk" -> { vault.chunk(tabId, payload); null }
                    "vault-snapshot-end" -> { vault.end(tabId, payload); null }
                    "vault-stage" -> {
                        payload.optString("chatId").takeIf { it.isNotBlank() }?.let(vault::stage)
                        null
                    }
                    "vault-pending-request" -> GeckoResult.fromValue(
                        vault.pendingResponse() ?: JSONObject().put("pending", false)
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
