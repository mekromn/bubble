package com.mekromn.bubble.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TabEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(TabTypeConverters::class)
abstract class BubbleDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao

    companion object {
        const val FILE_NAME = "bubble.db"
    }
}
