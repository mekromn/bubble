package com.mekromn.bubble.ai.monitor

import com.mekromn.bubble.browser.session.TabId

enum class AiChatSignal {
    USER_SUBMITTED,
    GENERATION_STARTED,
    GENERATION_FINISHED,
    ERROR,
}

fun interface AiChatSignalSink {
    fun onAiChatSignal(tabId: TabId, signal: AiChatSignal)
}
