package com.mekromn.bubble.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TabEntity::class,
        HeadPlacementEntity::class,
        SavedSessionEntity::class,
        SavedSessionTabEntity::class,
        HistoryEntryEntity::class,
        BookmarkEntity::class,
        ClosedTabEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(TabTypeConverters::class)
abstract class BubbleDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun headPlacementDao(): HeadPlacementDao
    abstract fun savedSessionDao(): SavedSessionDao
    abstract fun browsingDataDao(): BrowsingDataDao

    companion object {
        const val FILE_NAME = "bubble.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tabs ADD COLUMN keepRendererAlive INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS head_placements (
                        tabId TEXT NOT NULL,
                        normalizedX REAL NOT NULL,
                        normalizedY REAL NOT NULL,
                        displayId INTEGER,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(tabId),
                        FOREIGN KEY(tabId) REFERENCES tabs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_head_placements_updatedAt ON head_placements(updatedAt)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_sessions (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_sessions_updatedAt ON saved_sessions(updatedAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_session_tabs (
                        sessionId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        lastCommittedUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        presentationState TEXT NOT NULL,
                        pinned INTEGER NOT NULL,
                        keepRendererAlive INTEGER NOT NULL,
                        userAgentMode TEXT NOT NULL,
                        zoomPercent INTEGER NOT NULL,
                        groupKey TEXT,
                        normalizedHeadX REAL,
                        normalizedHeadY REAL,
                        PRIMARY KEY(sessionId, position),
                        FOREIGN KEY(sessionId) REFERENCES saved_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_session_tabs_sessionId ON saved_session_tabs(sessionId)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history_entries (
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        lastVisitedAt INTEGER NOT NULL,
                        visitCount INTEGER NOT NULL,
                        PRIMARY KEY(url)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_entries_lastVisitedAt ON history_entries(lastVisitedAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(url)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_createdAt ON bookmarks(createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bookmarks_updatedAt ON bookmarks(updatedAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS closed_tabs (
                        id TEXT NOT NULL,
                        originalTabId TEXT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        closedAt INTEGER NOT NULL,
                        presentationState TEXT NOT NULL,
                        userAgentMode TEXT NOT NULL,
                        zoomPercent INTEGER NOT NULL,
                        pinned INTEGER NOT NULL,
                        keepRendererAlive INTEGER NOT NULL,
                        groupId TEXT,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_closed_tabs_closedAt ON closed_tabs(closedAt)",
                )
            }
        }
    }
}
