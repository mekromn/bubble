package com.mekromn.bubble.data.db

import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TabRepository {
    fun observeAll(): Flow<List<Tab>>
    suspend fun loadAll(): List<Tab>
    suspend fun applyChanges(upserts: List<Tab>, deletes: List<TabId> = emptyList())
}

class RoomTabRepository(
    private val dao: TabDao,
) : TabRepository {
    override fun observeAll(): Flow<List<Tab>> = dao.observeAll().map { rows ->
        rows.map(TabEntity::toDomain)
    }

    override suspend fun loadAll(): List<Tab> = dao.loadAll().map(TabEntity::toDomain)

    override suspend fun applyChanges(upserts: List<Tab>, deletes: List<TabId>) {
        dao.applyChanges(
            upserts = upserts.map(Tab::toEntity),
            deletes = deletes.map(TabId::value),
        )
    }
}
