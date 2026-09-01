package com.mekromn.bubble.ai.chatgpt

import com.mekromn.bubble.ai.adapter.AiChatAdapter
import com.mekromn.bubble.ai.adapter.AiConversationLocation
import com.mekromn.bubble.ai.model.AiProvider
import java.net.URI

class ChatGptAdapter : AiChatAdapter {
    override val provider: AiProvider = AiProvider.CHATGPT

    override fun matchesTrustedOrigin(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals(TRUSTED_HOST, ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        return true
    }

    override fun conversationLocation(url: String): AiConversationLocation? {
        if (!matchesTrustedOrigin(url)) return null
        val uri = URI(url.trim())
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val conversationId = segments
            .takeIf { it.size >= 2 && it[0].equals("c", ignoreCase = true) }
            ?.get(1)
            ?.takeIf(String::isNotBlank)
        return AiConversationLocation(provider = provider, conversationId = conversationId)
    }

    companion object {
        const val TRUSTED_ORIGIN = "https://chatgpt.com"
        const val TRUSTED_HOST = "chatgpt.com"
    }
}
