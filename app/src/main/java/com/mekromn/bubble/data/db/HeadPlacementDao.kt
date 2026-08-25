package com.mekromn.bubble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HeadPlacementDao {
    @Query("SELECT * FROM head_placements")
    fun observeAll(): Flow<List<HeadPlacementEntity>>

    @Query("SELECT * FROM head_placements WHERE tabId = :tabId LIMIT 1")
    suspend fun get(tabId: String): HeadPlacementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(placement: HeadPlacementEntity)

    @Query("DELETE FROM head_placements WHERE tabId = :tabId")
    suspend fun delete(tabId: String)
}
