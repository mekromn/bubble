package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mekromn.bubble.ai.model.AiChatState
import com.mekromn.bubble.ai.model.AiChatTabStatus
import com.mekromn.bubble.ai.model.AiProvider
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.ai.model.WorkspaceId
import com.mekromn.bubble.browser.session.TabId

@Entity(
    tableName = "ai_workspaces",
    indices = [
        Index(value = ["provider", "profileId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class AiWorkspaceEntity(
    @PrimaryKey val workspaceId: String,
    val provider: String,
    val profileId: String,
    val lastActiveTabId: String?,
    val collapsedToBubble: Boolean,
    val notificationsEnabled: Boolean,
    val soundEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "ai_workspace_tabs",
    foreignKeys = [
        ForeignKey(
            entity = AiWorkspaceEntity::class,
            parentColumns = ["workspaceId"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["id"],
            childColumns = ["tabId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workspaceId"]),
        Index(value = ["state"]),
    ],
)
data class AiWorkspaceTabEntity(
    @PrimaryKey val tabId: String,
    val workspaceId: String,
    val state: String,
    val mutedNotifications: Boolean,
    val conversationTitle: String?,
    val lastStateChangeAt: Long,
    val generationSequence: Long,
    val lastNotifiedGenerationSequence: Long,
)

internal fun AiWorkspaceEntity.toDomain(members: List<AiWorkspaceTabEntity>): ChatWorkspace {
    val providerValue = runCatching { AiProvider.valueOf(provider) }.getOrDefault(AiProvider.CHATGPT)
    val chats = members
        .filter { it.workspaceId == workspaceId }
        .map(AiWorkspaceTabEntity::toDomain)
    return ChatWorkspace(
        id = WorkspaceId(workspaceId),
        provider = providerValue,
        profileId = profileId,
        tabIds = chats.map(AiChatTabStatus::tabId),
        lastActiveTabId = lastActiveTabId?.let(::TabId),
        collapsedToBubble = collapsedToBubble,
        notificationsEnabled = notificationsEnabled,
        soundEnabled = soundEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chats = chats,
    )
}

internal fun AiWorkspaceTabEntity.toDomain(): AiChatTabStatus = AiChatTabStatus(
    tabId = TabId(tabId),
    workspaceId = WorkspaceId(workspaceId),
    state = runCatching { AiChatState.valueOf(state) }.getOrDefault(AiChatState.UNKNOWN),
    mutedNotifications = mutedNotifications,
    conversationTitle = conversationTitle,
    lastStateChangeAt = lastStateChangeAt,
    generationSequence = generationSequence,
    lastNotifiedGenerationSequence = lastNotifiedGenerationSequence,
)

internal fun ChatWorkspace.toEntity(): AiWorkspaceEntity = AiWorkspaceEntity(
    workspaceId = id.value,
    provider = provider.name,
    profileId = profileId,
    lastActiveTabId = lastActiveTabId?.value,
    collapsedToBubble = collapsedToBubble,
    notificationsEnabled = notificationsEnabled,
    soundEnabled = soundEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun AiChatTabStatus.toEntity(): AiWorkspaceTabEntity = AiWorkspaceTabEntity(
    tabId = tabId.value,
    workspaceId = workspaceId.value,
    state = state.name,
    mutedNotifications = mutedNotifications,
    conversationTitle = conversationTitle,
    lastStateChangeAt = lastStateChangeAt,
    generationSequence = generationSequence,
    lastNotifiedGenerationSequence = lastNotifiedGenerationSequence,
)
