package com.mekromn.bubble.data

import android.content.Context
import androidx.room.Room
import com.mekromn.bubble.data.db.BubbleDatabase
import com.mekromn.bubble.data.db.RoomBrowsingDataRepository
import com.mekromn.bubble.data.db.RoomHeadPlacementRepository
import com.mekromn.bubble.data.db.RoomSavedSessionRepository
import com.mekromn.bubble.data.db.RoomTabRepository
import com.mekromn.bubble.data.settings.BrowserSettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: BubbleDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            BubbleDatabase::class.java,
            BubbleDatabase.FILE_NAME,
        ).addMigrations(
            BubbleDatabase.MIGRATION_1_2,
            BubbleDatabase.MIGRATION_2_3,
            BubbleDatabase.MIGRATION_3_4,
        ).build()
    }

    val tabs: RoomTabRepository by lazy { RoomTabRepository(database.tabDao()) }
    val headPlacements: RoomHeadPlacementRepository by lazy {
        RoomHeadPlacementRepository(database.headPlacementDao())
    }
    val savedSessions: RoomSavedSessionRepository by lazy {
        RoomSavedSessionRepository(database.savedSessionDao())
    }
    val browsingData: RoomBrowsingDataRepository by lazy {
        RoomBrowsingDataRepository(database.browsingDataDao())
    }
    val settings: BrowserSettingsRepository by lazy { BrowserSettingsRepository(appContext) }
}
