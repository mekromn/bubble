package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    indices = [Index(value = ["lastVisitedAt"])],
)
data class HistoryEntryEntity(
    @PrimaryKey val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["createdAt"]), Index(value = ["updatedAt"])],
)
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "closed_tabs",
    indices = [Index(value = ["closedAt"])],
)
data class ClosedTabEntity(
    @PrimaryKey val id: String,
    val originalTabId: String,
    val url: String,
    val title: String,
    val closedAt: Long,
    val presentationState: String,
    val userAgentMode: String,
    val zoomPercent: Int,
    val pinned: Boolean,
    val keepRendererAlive: Boolean,
    val groupId: String?,
)
