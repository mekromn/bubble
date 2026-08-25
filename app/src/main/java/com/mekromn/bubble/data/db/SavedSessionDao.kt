package com.mekromn.bubble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SavedSessionDao {
    @Query("SELECT * FROM saved_sessions ORDER BY updatedAt DESC")
    abstract fun observeSessions(): Flow<List<SavedSessionEntity>>

    @Query("SELECT * FROM saved_sessions WHERE id = :id LIMIT 1")
    protected abstract suspend fun getSessionEntity(id: String): SavedSessionEntity?

    @Query("SELECT * FROM saved_session_tabs WHERE sessionId = :id ORDER BY position ASC")
    protected abstract suspend fun getTabEntities(id: String): List<SavedSessionTabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertSessionEntity(entity: SavedSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertTabEntities(entities: List<SavedSessionTabEntity>)

    @Query("DELETE FROM saved_session_tabs WHERE sessionId = :id")
    protected abstract suspend fun deleteTabEntities(id: String)

    @Query("DELETE FROM saved_sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: String)

    @Transaction
    open suspend fun loadSession(id: String): SavedSessionRows? {
        val session = getSessionEntity(id) ?: return null
        return SavedSessionRows(session, getTabEntities(id))
    }

    @Transaction
    open suspend fun saveSession(
        session: SavedSessionEntity,
        tabs: List<SavedSessionTabEntity>,
    ) {
        upsertSessionEntity(session)
        deleteTabEntities(session.id)
        if (tabs.isNotEmpty()) upsertTabEntities(tabs)
    }
}

data class SavedSessionRows(
    val session: SavedSessionEntity,
    val tabs: List<SavedSessionTabEntity>,
)
