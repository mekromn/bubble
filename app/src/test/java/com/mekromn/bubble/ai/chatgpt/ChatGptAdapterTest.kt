package com.mekromn.bubble.ai.chatgpt

import com.mekromn.bubble.ai.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAdapterTest {
    private val adapter = ChatGptAdapter()

    @Test
    fun trustedOriginRequiresExactHttpsChatGptHost() {
        assertTrue(adapter.matchesTrustedOrigin("https://chatgpt.com/"))
        assertTrue(adapter.matchesTrustedOrigin("https://chatgpt.com/c/abc-123?x=1"))
        assertTrue(adapter.matchesTrustedOrigin("https://chatgpt.com:443/c/abc"))

        assertFalse(adapter.matchesTrustedOrigin("http://chatgpt.com/c/abc"))
        assertFalse(adapter.matchesTrustedOrigin("https://www.chatgpt.com/c/abc"))
        assertFalse(adapter.matchesTrustedOrigin("https://chatgpt.com.evil.example/c/abc"))
        assertFalse(adapter.matchesTrustedOrigin("https://evil.example/?next=https://chatgpt.com"))
        assertFalse(adapter.matchesTrustedOrigin("javascript:alert(1)"))
    }

    @Test
    fun conversationLocationExtractsKnownConversationIdWithoutUsingItAsIdentity() {
        val location = adapter.conversationLocation("https://chatgpt.com/c/conversation-42")
        assertEquals(AiProvider.CHATGPT, location?.provider)
        assertEquals("conversation-42", location?.conversationId)
        assertNull(adapter.conversationLocation("https://example.com/c/conversation-42"))
    }
}
