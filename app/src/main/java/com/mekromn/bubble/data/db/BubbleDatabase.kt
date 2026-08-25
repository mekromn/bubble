package com.mekromn.bubble.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TabEntity::class, HeadPlacementEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(TabTypeConverters::class)
abstract class BubbleDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun headPlacementDao(): HeadPlacementDao

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
    }
}
