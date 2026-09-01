package com.mekromn.bubble.ai.workspace

import com.mekromn.bubble.ai.adapter.AiChatAdapter
import com.mekromn.bubble.ai.model.AiChatState
import com.mekromn.bubble.ai.model.AiChatTabStatus
import com.mekromn.bubble.ai.model.AiWorkspaceState
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.ai.model.WorkspaceId
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.data.db.AiWorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AiWorkspaceCoordinator(
    private val repository: AiWorkspaceRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AiWorkspaceState())
    val state: StateFlow<AiWorkspaceState> = mutableState
    private var observationJob: Job? = null

    suspend fun initialize() {
        mutex.withLock {
            if (!mutableState.value.initialized) {
                mutableState.value = repository.loadState()
            }
            if (observationJob == null) {
                observationJob = scope.launch {
                    repository.observeState().collect { mutableState.value = it }
                }
            }
        }
    }

    suspend fun ensureMembership(tab: Tab, adapter: AiChatAdapter) {
        if (tab.isPrivate) return
        initialize()
        mutex.withLock {
            val now = clock()
            val workspaceId = stableWorkspaceId(adapter, tab.profileId)
            val currentWorkspace = mutableState.value.workspaces.firstOrNull { it.id == workspaceId }
            val workspace = currentWorkspace ?: ChatWorkspace(
                id = workspaceId,
                provider = adapter.provider,
                profileId = tab.profileId,
                tabIds = emptyList(),
                lastActiveTabId = if (tab.selected) tab.id else null,
                collapsedToBubble = false,
                notificationsEnabled = true,
                soundEnabled = true,
                createdAt = now,
                updatedAt = now,
                chats = emptyList(),
            )
            val updatedWorkspace = workspace.copy(
                lastActiveTabId = if (tab.selected) tab.id else workspace.lastActiveTabId,
                updatedAt = now,
            )
            repository.upsertWorkspace(updatedWorkspace)

            val existing = workspace.chats.firstOrNull { it.tabId == tab.id }
            repository.upsertMember(
                (existing ?: AiChatTabStatus(
                    tabId = tab.id,
                    workspaceId = workspaceId,
                    state = AiChatState.IDLE,
                    lastStateChangeAt = now,
                )).copy(
                    conversationTitle = tab.title.takeUnless { it.isBlank() || it == "New tab" },
                ),
            )
        }
    }

    suspend fun setLastActive(tabId: TabId) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            repository.upsertWorkspace(
                workspace.copy(lastActiveTabId = tabId, updatedAt = clock()),
            )
        }
    }

    suspend fun setCollapsed(workspaceId: WorkspaceId, collapsed: Boolean) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaces.firstOrNull { it.id == workspaceId }
                ?: return@withLock
            repository.upsertWorkspace(
                workspace.copy(collapsedToBubble = collapsed, updatedAt = clock()),
            )
        }
    }

    suspend fun updateChatState(tabId: TabId, state: AiChatState) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (current.state == state) return@withLock
            val nextSequence = when {
                state == AiChatState.GENERATING && current.state != AiChatState.GENERATING -> {
                    current.generationSequence + 1L
                }
                else -> current.generationSequence
            }
            repository.upsertMember(
                current.copy(
                    state = state,
                    lastStateChangeAt = clock(),
                    generationSequence = nextSequence,
                ),
            )
        }
    }

    suspend fun markCompletionNotified(tabId: TabId, generationSequence: Long) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (generationSequence <= current.lastNotifiedGenerationSequence) return@withLock
            repository.upsertMember(
                current.copy(lastNotifiedGenerationSequence = generationSequence),
            )
        }
    }

    suspend fun markRead(tabId: TabId) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (current.state != AiChatState.COMPLETE_UNREAD) return@withLock
            repository.upsertMember(
                current.copy(state = AiChatState.COMPLETE_READ, lastStateChangeAt = clock()),
            )
        }
    }

    suspend fun removeMembership(tabId: TabId) {
        repository.deleteMember(tabId)
    }

    fun workspaceForTab(tabId: TabId): ChatWorkspace? = state.value.workspaceForTab(tabId)

    private fun stableWorkspaceId(adapter: AiChatAdapter, profileId: String): WorkspaceId =
        WorkspaceId("${adapter.provider.name.lowercase()}:$profileId")
}
