package com.mekromn.bubble.ai.model

import com.mekromn.bubble.browser.session.TabId
import java.util.UUID

@JvmInline
value class WorkspaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "WorkspaceId must not be blank" }
    }

    companion object {
        fun newId(): WorkspaceId = WorkspaceId(UUID.randomUUID().toString())
    }
}

enum class AiProvider {
    CHATGPT,
}

enum class AiChatState {
    UNKNOWN,
    IDLE,
    USER_SUBMITTED,
    GENERATING,
    COMPLETE_UNREAD,
    COMPLETE_READ,
    ERROR,
    RECOVERING,
}

data class AiChatTabStatus(
    val tabId: TabId,
    val workspaceId: WorkspaceId,
    val state: AiChatState = AiChatState.UNKNOWN,
    val mutedNotifications: Boolean = false,
    val conversationTitle: String? = null,
    val lastStateChangeAt: Long,
    val generationSequence: Long = 0L,
    val lastNotifiedGenerationSequence: Long = 0L,
) {
    val hasUnreadCompletion: Boolean
        get() = state == AiChatState.COMPLETE_UNREAD

    val hasUnnotifiedCompletion: Boolean
        get() = hasUnreadCompletion && generationSequence > lastNotifiedGenerationSequence
}

data class ChatWorkspace(
    val id: WorkspaceId,
    val provider: AiProvider,
    val profileId: String,
    val tabIds: List<TabId>,
    val lastActiveTabId: TabId?,
    val collapsedToBubble: Boolean,
    val notificationsEnabled: Boolean,
    val soundEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val chats: List<AiChatTabStatus>,
) {
    val generatingCount: Int
        get() = chats.count { it.state == AiChatState.GENERATING }

    val unreadCompletedCount: Int
        get() = chats.count(AiChatTabStatus::hasUnreadCompletion)

    val recoveringCount: Int
        get() = chats.count { it.state == AiChatState.RECOVERING }
}

data class AiWorkspaceState(
    val initialized: Boolean = false,
    val workspaces: List<ChatWorkspace> = emptyList(),
) {
    fun workspaceForTab(tabId: TabId): ChatWorkspace? = workspaces.firstOrNull { tabId in it.tabIds }
}
