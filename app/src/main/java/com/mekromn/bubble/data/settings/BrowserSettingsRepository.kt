package com.mekromn.bubble.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mekromn.bubble.browser.session.RendererMemoryMode
import com.mekromn.bubble.display.RefreshRateMode
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
    val rendererMemoryMode: RendererMemoryMode = RendererMemoryMode.BALANCED,
    val refreshRateMode: RefreshRateMode = RefreshRateMode.HZ_120_PLUS,
)

class BrowserSettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val searchEngine = stringPreferencesKey("search_engine")
        val restorePreviousSession = booleanPreferencesKey("restore_previous_session")
        val rendererMemoryMode = stringPreferencesKey("renderer_memory_mode")
        val refreshRateMode = stringPreferencesKey("refresh_rate_mode")
    }

    val settings: Flow<BrowserSettings> = context.browserSettingsDataStore.data.map { prefs ->
        BrowserSettings(
            searchEngine = prefs[Keys.searchEngine]
                ?.let { stored -> SearchEngine.entries.firstOrNull { it.name == stored } }
                ?: SearchEngine.GOOGLE,
            restorePreviousSession = prefs[Keys.restorePreviousSession] ?: true,
            rendererMemoryMode = prefs[Keys.rendererMemoryMode]
                ?.let { stored -> RendererMemoryMode.entries.firstOrNull { it.name == stored } }
                ?: RendererMemoryMode.BALANCED,
            refreshRateMode = prefs[Keys.refreshRateMode]
                ?.let { stored -> RefreshRateMode.entries.firstOrNull { it.name == stored } }
                ?: RefreshRateMode.HZ_120_PLUS,
        )
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.browserSettingsDataStore.edit { it[Keys.searchEngine] = engine.name }
    }

    suspend fun setRestorePreviousSession(enabled: Boolean) {
        context.browserSettingsDataStore.edit { it[Keys.restorePreviousSession] = enabled }
    }

    suspend fun setRendererMemoryMode(mode: RendererMemoryMode) {
        context.browserSettingsDataStore.edit { it[Keys.rendererMemoryMode] = mode.name }
    }

    suspend fun setRefreshRateMode(mode: RefreshRateMode) {
        context.browserSettingsDataStore.edit { it[Keys.refreshRateMode] = mode.name }
    }
}
