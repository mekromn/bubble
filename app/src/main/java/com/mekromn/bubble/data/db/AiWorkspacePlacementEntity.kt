package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mekromn.bubble.ai.model.WorkspaceId

data class AiWorkspacePlacement(
    val workspaceId: WorkspaceId,
    val normalizedX: Float,
    val normalizedY: Float,
    val displayId: Int?,
    val updatedAt: Long,
)

@Entity(
    tableName = "ai_workspace_placements",
    foreignKeys = [
        ForeignKey(
            entity = AiWorkspaceEntity::class,
            parentColumns = ["workspaceId"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["updatedAt"])],
)
data class AiWorkspacePlacementEntity(
    @PrimaryKey val workspaceId: String,
    val normalizedX: Float,
    val normalizedY: Float,
    val displayId: Int?,
    val updatedAt: Long,
)

internal fun AiWorkspacePlacementEntity.toDomain(): AiWorkspacePlacement = AiWorkspacePlacement(
    workspaceId = WorkspaceId(workspaceId),
    normalizedX = normalizedX,
    normalizedY = normalizedY,
    displayId = displayId,
    updatedAt = updatedAt,
)

internal fun AiWorkspacePlacement.toEntity(): AiWorkspacePlacementEntity = AiWorkspacePlacementEntity(
    workspaceId = workspaceId.value,
    normalizedX = normalizedX,
    normalizedY = normalizedY,
    displayId = displayId,
    updatedAt = updatedAt,
)
