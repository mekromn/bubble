package com.mekromn.bubble.ai.workspace

import com.mekromn.bubble.ai.adapter.AiChatAdapter
import com.mekromn.bubble.ai.model.AiChatState
import com.mekromn.bubble.ai.model.AiChatTabStatus
import com.mekromn.bubble.ai.model.AiWorkspaceState
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.ai.model.WorkspaceId
import com.mekromn.bubble.ai.monitor.AiChatSignal
import com.mekromn.bubble.ai.monitor.AiChatSignalSink
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.data.db.AiWorkspacePlacement
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
) : AiChatSignalSink {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AiWorkspaceState())
    val state: StateFlow<AiWorkspaceState> = mutableState
    private var observationJob: Job? = null

    suspend fun initialize() {
        mutex.withLock {
            if (!mutableState.value.initialized) mutableState.value = repository.loadState()
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
            val existing = workspace.chats.firstOrNull { it.tabId == tab.id }
            val member = (existing ?: AiChatTabStatus(
                tabId = tab.id,
                workspaceId = workspaceId,
                state = AiChatState.IDLE,
                lastStateChangeAt = now,
            )).copy(
                conversationTitle = tab.title.takeUnless { it.isBlank() || it == "New tab" },
            )
            val chats = workspace.chats.filterNot { it.tabId == tab.id } + member
            val updated = workspace.copy(
                tabIds = chats.map(AiChatTabStatus::tabId),
                lastActiveTabId = if (tab.selected) tab.id else workspace.lastActiveTabId,
                updatedAt = now,
                chats = chats,
            )
            repository.upsertWorkspace(updated)
            if (existing != member) repository.upsertMember(member)
            replaceWorkspace(updated)
        }
    }

    suspend fun setLastActive(tabId: TabId) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            if (workspace.lastActiveTabId == tabId) return@withLock
            val updated = workspace.copy(lastActiveTabId = tabId, updatedAt = clock())
            repository.upsertWorkspace(updated)
            replaceWorkspace(updated)
        }
    }

    suspend fun setCollapsed(workspaceId: WorkspaceId, collapsed: Boolean) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaces.firstOrNull { it.id == workspaceId }
                ?: return@withLock
            if (workspace.collapsedToBubble == collapsed) return@withLock
            val updated = workspace.copy(collapsedToBubble = collapsed, updatedAt = clock())
            repository.upsertWorkspace(updated)
            replaceWorkspace(updated)
        }
    }

    suspend fun updateChatState(tabId: TabId, state: AiChatState) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (current.state == state) return@withLock
            val nextSequence = if (state == AiChatState.GENERATING && current.state != AiChatState.GENERATING) {
                current.generationSequence + 1L
            } else {
                current.generationSequence
            }
            val member = current.copy(
                state = state,
                lastStateChangeAt = clock(),
                generationSequence = nextSequence,
            )
            repository.upsertMember(member)
            replaceMember(workspace, member)
        }
    }

    override fun onAiChatSignal(tabId: TabId, signal: AiChatSignal) {
        scope.launch { applySignal(tabId, signal) }
    }

    private suspend fun applySignal(tabId: TabId, signal: AiChatSignal) {
        initialize()
        val current = state.value.workspaceForTab(tabId)
            ?.chats
            ?.firstOrNull { it.tabId == tabId }
            ?: return
        when (signal) {
            AiChatSignal.USER_SUBMITTED -> updateChatState(tabId, AiChatState.USER_SUBMITTED)
            AiChatSignal.GENERATION_STARTED -> updateChatState(tabId, AiChatState.GENERATING)
            AiChatSignal.GENERATION_FINISHED -> {
                if (current.state == AiChatState.GENERATING || current.state == AiChatState.USER_SUBMITTED) {
                    updateChatState(tabId, AiChatState.COMPLETE_UNREAD)
                }
            }
            AiChatSignal.ERROR -> updateChatState(tabId, AiChatState.ERROR)
        }
    }

    suspend fun markCompletionNotified(tabId: TabId, generationSequence: Long) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (generationSequence <= current.lastNotifiedGenerationSequence) return@withLock
            val member = current.copy(lastNotifiedGenerationSequence = generationSequence)
            repository.upsertMember(member)
            replaceMember(workspace, member)
        }
    }

    suspend fun markRead(tabId: TabId) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId) ?: return@withLock
            val current = workspace.chats.firstOrNull { it.tabId == tabId } ?: return@withLock
            if (current.state != AiChatState.COMPLETE_UNREAD) return@withLock
            val member = current.copy(state = AiChatState.COMPLETE_READ, lastStateChangeAt = clock())
            repository.upsertMember(member)
            replaceMember(workspace, member)
        }
    }

    suspend fun removeMembership(tabId: TabId) {
        initialize()
        mutex.withLock {
            val workspace = mutableState.value.workspaceForTab(tabId)
            repository.deleteMember(tabId)
            if (workspace == null) return@withLock
            val chats = workspace.chats.filterNot { it.tabId == tabId }
            if (chats.isEmpty()) {
                repository.deleteWorkspace(workspace.id.value)
                mutableState.value = mutableState.value.copy(
                    initialized = true,
                    workspaces = mutableState.value.workspaces.filterNot { it.id == workspace.id },
                )
            } else {
                val updated = workspace.copy(
                    tabIds = chats.map(AiChatTabStatus::tabId),
                    lastActiveTabId = workspace.lastActiveTabId?.takeIf { it != tabId }
                        ?: chats.maxByOrNull(AiChatTabStatus::lastStateChangeAt)?.tabId,
                    updatedAt = clock(),
                    chats = chats,
                )
                repository.upsertWorkspace(updated)
                replaceWorkspace(updated)
            }
        }
    }

    suspend fun getPlacement(workspaceId: WorkspaceId): AiWorkspacePlacement? =
        repository.getPlacement(workspaceId.value)

    suspend fun savePlacement(placement: AiWorkspacePlacement) {
        repository.savePlacement(placement)
    }

    fun workspaceForTab(tabId: TabId): ChatWorkspace? = state.value.workspaceForTab(tabId)

    private fun replaceMember(workspace: ChatWorkspace, member: AiChatTabStatus) {
        val chats = workspace.chats.map { if (it.tabId == member.tabId) member else it }
        replaceWorkspace(workspace.copy(tabIds = chats.map(AiChatTabStatus::tabId), chats = chats))
    }

    private fun replaceWorkspace(updated: ChatWorkspace) {
        val current = mutableState.value.workspaces
        val index = current.indexOfFirst { it.id == updated.id }
        val next = if (index < 0) {
            current + updated
        } else {
            current.toMutableList().also { it[index] = updated }
        }
        mutableState.value = AiWorkspaceState(initialized = true, workspaces = next)
    }

    private fun stableWorkspaceId(adapter: AiChatAdapter, profileId: String): WorkspaceId =
        WorkspaceId("${adapter.provider.name.lowercase()}:$profileId")
}
