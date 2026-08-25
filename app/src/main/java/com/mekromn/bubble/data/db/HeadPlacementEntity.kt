package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "head_placements",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["id"],
            childColumns = ["tabId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["updatedAt"])],
)
data class HeadPlacementEntity(
    @PrimaryKey val tabId: String,
    val normalizedX: Float,
    val normalizedY: Float,
    val displayId: Int?,
    val updatedAt: Long,
)
