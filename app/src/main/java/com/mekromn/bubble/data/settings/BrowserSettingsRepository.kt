package com.mekromn.bubble.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.browserSettingsDataStore by preferencesDataStore(name = "browser_settings")

enum class SearchEngine(val queryTemplate: String) {
    GOOGLE("https://www.google.com/search?q=%s"),
    DUCK_DUCK_GO("https://duckduckgo.com/?q=%s"),
}

data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val restorePreviousSession: Boolean = true,
)

class BrowserSettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val searchEngine = stringPreferencesKey("search_engine")
        val restorePreviousSession = booleanPreferencesKey("restore_previous_session")
    }

    val settings: Flow<BrowserSettings> = context.browserSettingsDataStore.data.map { prefs ->
        BrowserSettings(
            searchEngine = prefs[Keys.searchEngine]
                ?.let { stored -> SearchEngine.entries.firstOrNull { it.name == stored } }
                ?: SearchEngine.GOOGLE,
            restorePreviousSession = prefs[Keys.restorePreviousSession] ?: true,
        )
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.browserSettingsDataStore.edit { it[Keys.searchEngine] = engine.name }
    }

    suspend fun setRestorePreviousSession(enabled: Boolean) {
        context.browserSettingsDataStore.edit { it[Keys.restorePreviousSession] = enabled }
    }
}
