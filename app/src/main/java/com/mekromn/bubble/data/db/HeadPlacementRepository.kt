package com.mekromn.bubble.data.db

import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class HeadPlacement(
    val tabId: TabId,
    val normalizedX: Float,
    val normalizedY: Float,
    val displayId: Int?,
    val updatedAt: Long,
)

interface HeadPlacementRepository {
    fun observeAll(): Flow<List<HeadPlacement>>
    suspend fun get(tabId: TabId): HeadPlacement?
    suspend fun save(placement: HeadPlacement)
    suspend fun delete(tabId: TabId)
}

class RoomHeadPlacementRepository(
    private val dao: HeadPlacementDao,
) : HeadPlacementRepository {
    override fun observeAll(): Flow<List<HeadPlacement>> = dao.observeAll().map { rows ->
        rows.map { row ->
            HeadPlacement(
                tabId = TabId(row.tabId),
                normalizedX = row.normalizedX,
                normalizedY = row.normalizedY,
                displayId = row.displayId,
                updatedAt = row.updatedAt,
            )
        }
    }

    override suspend fun get(tabId: TabId): HeadPlacement? = dao.get(tabId.value)?.let { row ->
        HeadPlacement(
            tabId = tabId,
            normalizedX = row.normalizedX,
            normalizedY = row.normalizedY,
            displayId = row.displayId,
            updatedAt = row.updatedAt,
        )
    }

    override suspend fun save(placement: HeadPlacement) {
        dao.upsert(
            HeadPlacementEntity(
                tabId = placement.tabId.value,
                normalizedX = placement.normalizedX.coerceIn(0f, 1f),
                normalizedY = placement.normalizedY.coerceIn(0f, 1f),
                displayId = placement.displayId,
                updatedAt = placement.updatedAt,
            ),
        )
    }

    override suspend fun delete(tabId: TabId) = dao.delete(tabId.value)
}
