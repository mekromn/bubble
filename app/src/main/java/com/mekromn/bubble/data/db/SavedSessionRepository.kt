package com.mekromn.bubble.data.db

import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.UserAgentMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavedSessionSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SavedSessionTabSnapshot(
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

data class SavedSessionSnapshot(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tabs: List<SavedSessionTabSnapshot>,
)

enum class SavedSessionRestoreMode {
    REPLACE,
    MERGE,
    ADD_ALL,
}

interface SavedSessionRepository {
    fun observeSummaries(): Flow<List<SavedSessionSummary>>
    suspend fun load(id: String): SavedSessionSnapshot?
    suspend fun save(snapshot: SavedSessionSnapshot)
    suspend fun delete(id: String)
}

class RoomSavedSessionRepository(
    private val dao: SavedSessionDao,
) : SavedSessionRepository {
    override fun observeSummaries(): Flow<List<SavedSessionSummary>> = dao.observeSessions().map { rows ->
        rows.map { row ->
            SavedSessionSummary(row.id, row.name, row.createdAt, row.updatedAt)
        }
    }

    override suspend fun load(id: String): SavedSessionSnapshot? = dao.loadSession(id)?.let { rows ->
        SavedSessionSnapshot(
            id = rows.session.id,
            name = rows.session.name,
            createdAt = rows.session.createdAt,
            updatedAt = rows.session.updatedAt,
            tabs = rows.tabs.map { row ->
                SavedSessionTabSnapshot(
                    lastCommittedUrl = row.lastCommittedUrl,
                    title = row.title,
                    presentationState = row.presentationState,
                    pinned = row.pinned,
                    keepRendererAlive = row.keepRendererAlive,
                    userAgentMode = row.userAgentMode,
                    zoomPercent = row.zoomPercent,
                    groupKey = row.groupKey,
                    normalizedHeadX = row.normalizedHeadX,
                    normalizedHeadY = row.normalizedHeadY,
                )
            },
        )
    }

    override suspend fun save(snapshot: SavedSessionSnapshot) {
        val id = snapshot.id.ifBlank { UUID.randomUUID().toString() }
        dao.saveSession(
            session = SavedSessionEntity(
                id = id,
                name = snapshot.name,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
            ),
            tabs = snapshot.tabs.mapIndexed { position, tab ->
                SavedSessionTabEntity(
                    sessionId = id,
                    position = position,
                    lastCommittedUrl = tab.lastCommittedUrl,
                    title = tab.title,
                    presentationState = tab.presentationState,
                    pinned = tab.pinned,
                    keepRendererAlive = tab.keepRendererAlive,
                    userAgentMode = tab.userAgentMode,
                    zoomPercent = tab.zoomPercent,
                    groupKey = tab.groupKey,
                    normalizedHeadX = tab.normalizedHeadX,
                    normalizedHeadY = tab.normalizedHeadY,
                )
            },
        )
    }

    override suspend fun delete(id: String) = dao.deleteSession(id)
}
