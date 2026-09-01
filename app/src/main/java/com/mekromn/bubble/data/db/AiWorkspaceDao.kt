package com.mekromn.bubble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiWorkspaceDao {
    @Query("SELECT * FROM ai_workspaces ORDER BY updatedAt DESC")
    fun observeWorkspaces(): Flow<List<AiWorkspaceEntity>>

    @Query("SELECT * FROM ai_workspace_tabs")
    fun observeMembers(): Flow<List<AiWorkspaceTabEntity>>

    @Query("SELECT * FROM ai_workspaces ORDER BY updatedAt DESC")
    suspend fun loadWorkspaces(): List<AiWorkspaceEntity>

    @Query("SELECT * FROM ai_workspace_tabs")
    suspend fun loadMembers(): List<AiWorkspaceTabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(workspace: AiWorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: AiWorkspaceTabEntity)

    @Query("DELETE FROM ai_workspace_tabs WHERE tabId = :tabId")
    suspend fun deleteMember(tabId: String)

    @Query("DELETE FROM ai_workspaces WHERE workspaceId = :workspaceId")
    suspend fun deleteWorkspace(workspaceId: String)

    @Query("SELECT * FROM ai_workspace_placements WHERE workspaceId = :workspaceId LIMIT 1")
    suspend fun getPlacement(workspaceId: String): AiWorkspacePlacementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlacement(placement: AiWorkspacePlacementEntity)
}
