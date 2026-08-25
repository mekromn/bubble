package com.mekromn.bubble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BrowsingDataDao {
    @Query("SELECT * FROM history_entries ORDER BY lastVisitedAt DESC")
    abstract fun observeHistory(): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM history_entries WHERE url = :url LIMIT 1")
    protected abstract suspend fun historyForUrl(url: String): HistoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertHistory(entry: HistoryEntryEntity)

    @Transaction
    open suspend fun recordHistoryVisit(url: String, title: String, visitedAt: Long) {
        val previous = historyForUrl(url)
        upsertHistory(
            HistoryEntryEntity(
                url = url,
                title = title.ifBlank { previous?.title.orEmpty() },
                lastVisitedAt = visitedAt,
                visitCount = (previous?.visitCount ?: 0) + 1,
            ),
        )
    }

    @Query("DELETE FROM history_entries")
    abstract suspend fun clearHistory()

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC")
    abstract fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    abstract suspend fun bookmarkForUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    abstract suspend fun deleteBookmark(url: String)

    @Query("SELECT * FROM closed_tabs ORDER BY closedAt DESC")
    abstract fun observeClosedTabs(): Flow<List<ClosedTabEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertClosedTab(tab: ClosedTabEntity)

    @Query("DELETE FROM closed_tabs WHERE id = :id")
    abstract suspend fun deleteClosedTab(id: String)

    @Query("DELETE FROM closed_tabs WHERE id NOT IN (SELECT id FROM closed_tabs ORDER BY closedAt DESC LIMIT :keep)")
    protected abstract suspend fun trimClosedTabs(keep: Int)

    @Transaction
    open suspend fun recordClosedTab(tab: ClosedTabEntity, keep: Int = 100) {
        insertClosedTab(tab)
        trimClosedTabs(keep)
    }

    @Query("DELETE FROM closed_tabs")
    abstract suspend fun clearClosedTabs()
}
