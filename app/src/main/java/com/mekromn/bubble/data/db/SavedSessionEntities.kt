package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.UserAgentMode

@Entity(
    tableName = "saved_sessions",
    indices = [Index(value = ["updatedAt"])],
)
data class SavedSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "saved_session_tabs",
    primaryKeys = ["sessionId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = SavedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class SavedSessionTabEntity(
    val sessionId: String,
    val position: Int,
    val lastCommittedUrl: String,
    val title: String,
    val presentationState: PresentationState,
    val pinned: Boolean,
    val keepRendererAlive: Boolean,
    val userAgentMode: UserAgentMode,
    val zoomPercent: Int,
    val groupKey: String?,
    val normalizedHeadX: Float?,
    val normalizedHeadY: Float?,
)
