package com.mekromn.bubble.data.db

import com.mekromn.bubble.ai.model.AiChatTabStatus
import com.mekromn.bubble.ai.model.AiWorkspaceState
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface AiWorkspaceRepository {
    fun observeState(): Flow<AiWorkspaceState>
    suspend fun loadState(): AiWorkspaceState
    suspend fun upsertWorkspace(workspace: ChatWorkspace)
    suspend fun upsertMember(member: AiChatTabStatus)
    suspend fun deleteMember(tabId: TabId)
    suspend fun deleteWorkspace(workspaceId: String)
    suspend fun getPlacement(workspaceId: String): AiWorkspacePlacement?
    suspend fun savePlacement(placement: AiWorkspacePlacement)
}

class RoomAiWorkspaceRepository(
    private val dao: AiWorkspaceDao,
) : AiWorkspaceRepository {
    override fun observeState(): Flow<AiWorkspaceState> = combine(
        dao.observeWorkspaces(),
        dao.observeMembers(),
    ) { workspaces, members ->
        AiWorkspaceState(initialized = true, workspaces = workspaces.map { it.toDomain(members) })
    }

    override suspend fun loadState(): AiWorkspaceState = AiWorkspaceState(
        initialized = true,
        workspaces = dao.loadWorkspaces().let { workspaces ->
            val members = dao.loadMembers()
            workspaces.map { it.toDomain(members) }
        },
    )

    override suspend fun upsertWorkspace(workspace: ChatWorkspace) {
        dao.upsertWorkspace(workspace.toEntity())
    }

    override suspend fun upsertMember(member: AiChatTabStatus) {
        dao.upsertMember(member.toEntity())
    }

    override suspend fun deleteMember(tabId: TabId) {
        dao.deleteMember(tabId.value)
    }

    override suspend fun deleteWorkspace(workspaceId: String) {
        dao.deleteWorkspace(workspaceId)
    }

    override suspend fun getPlacement(workspaceId: String): AiWorkspacePlacement? =
        dao.getPlacement(workspaceId)?.toDomain()

    override suspend fun savePlacement(placement: AiWorkspacePlacement) {
        dao.upsertPlacement(placement.toEntity())
    }
}
