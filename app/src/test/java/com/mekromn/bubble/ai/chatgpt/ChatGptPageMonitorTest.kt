package com.mekromn.bubble.ai.chatgpt

import com.mekromn.bubble.ai.monitor.AiChatSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptPageMonitorTest {
    @Test
    fun signalParserAcceptsOnlyKnownLifecycleMessages() {
        assertEquals(AiChatSignal.USER_SUBMITTED, ChatGptPageMonitor.parseSignal("user_submitted"))
        assertEquals(AiChatSignal.GENERATION_STARTED, ChatGptPageMonitor.parseSignal("generation_started"))
        assertEquals(AiChatSignal.GENERATION_FINISHED, ChatGptPageMonitor.parseSignal("generation_finished"))
        assertEquals(AiChatSignal.ERROR, ChatGptPageMonitor.parseSignal("generation_error"))
        assertNull(ChatGptPageMonitor.parseSignal("response text that must never be accepted"))
        assertNull(ChatGptPageMonitor.parseSignal(null))
    }
}
