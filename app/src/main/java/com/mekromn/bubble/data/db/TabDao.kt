package com.mekromn.bubble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TabDao {
    @Query("SELECT * FROM tabs ORDER BY sortIndex ASC")
    abstract fun observeAll(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs ORDER BY sortIndex ASC")
    abstract suspend fun loadAll(): List<TabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertEntities(entities: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id IN (:ids)")
    protected abstract suspend fun deleteIds(ids: List<String>)

    @Transaction
    open suspend fun applyChanges(upserts: List<TabEntity>, deletes: List<String>) {
        if (deletes.isNotEmpty()) deleteIds(deletes)
        if (upserts.isNotEmpty()) upsertEntities(upserts)
    }
}
