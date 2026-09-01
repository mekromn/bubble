package com.mekromn.bubble.ai.adapter

import com.mekromn.bubble.ai.model.AiProvider

data class AiConversationLocation(
    val provider: AiProvider,
    val conversationId: String? = null,
)

interface AiChatAdapter {
    val provider: AiProvider

    /** Returns true only for origins this adapter is allowed to integrate with. */
    fun matchesTrustedOrigin(url: String): Boolean

    /** Provider-neutral location metadata; never required for durable TabId identity. */
    fun conversationLocation(url: String): AiConversationLocation? =
        if (matchesTrustedOrigin(url)) AiConversationLocation(provider) else null
}

class AiChatAdapterRegistry(
    private val adapters: List<AiChatAdapter>,
) {
    fun match(url: String): AiChatAdapter? = adapters.firstOrNull { it.matchesTrustedOrigin(url) }

    fun adapter(provider: AiProvider): AiChatAdapter? = adapters.firstOrNull { it.provider == provider }
}
