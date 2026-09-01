package com.mekromn.bubble.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mekromn.bubble.BubbleApplication
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import com.mekromn.bubble.data.db.SavedSessionRestoreMode
import com.mekromn.bubble.display.RefreshRateMode
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val bubbleApplication = application as BubbleApplication
    private val sessionManager = bubbleApplication.runtime.sessions
    private val rendererPool = bubbleApplication.runtime.rendererPool
    private val aiWorkspaces = bubbleApplication.runtime.aiWorkspaces
    private val settingsRepository = bubbleApplication.container.settings

    val sessionState = sessionManager.state
    val activeWebView = rendererPool.activeWebView
    val pageState = rendererPool.activePageState
    val aiWorkspaceState = aiWorkspaces.state
    val savedSessions = sessionManager.savedSessions
    val settings = settingsRepository.settings

    init {
        viewModelScope.launch { sessionManager.initialize() }
    }

    fun navigate(input: String) {
        viewModelScope.launch { sessionManager.navigate(input) }
    }

    fun createTab() {
        viewModelScope.launch { sessionManager.createTab() }
    }

    fun duplicateTab(tabId: TabId) {
        viewModelScope.launch { sessionManager.duplicateTab(tabId) }
    }

    fun activate(tabId: TabId) {
        viewModelScope.launch {
            sessionManager.activate(tabId)
            aiWorkspaces.markRead(tabId)
        }
    }

    fun close(tabId: TabId) {
        viewModelScope.launch { sessionManager.close(tabId) }
    }

    fun moveTab(tabId: TabId, newIndex: Int) {
        viewModelScope.launch { sessionManager.moveTab(tabId, newIndex) }
    }

    fun minimizeActiveToHead(onComplete: (TabId?) -> Unit) {
        viewModelScope.launch {
            onComplete(sessionManager.minimizeSelectedToHead())
        }
    }

    fun setUserAgentMode(tabId: TabId, mode: UserAgentMode) {
        viewModelScope.launch { sessionManager.setUserAgentMode(tabId, mode) }
    }

    fun setRefreshRateMode(mode: RefreshRateMode) {
        viewModelScope.launch { settingsRepository.setRefreshRateMode(mode) }
    }

    fun saveCurrentSession(name: String, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            onSaved(sessionManager.saveCurrentSession(name))
        }
    }

    fun restoreSavedSession(
        id: String,
        mode: SavedSessionRestoreMode,
        onRestored: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onRestored(sessionManager.restoreSavedSession(id, mode))
        }
    }

    fun deleteSavedSession(id: String) {
        viewModelScope.launch { sessionManager.deleteSavedSession(id) }
    }

    fun goBack(): Boolean = sessionManager.goBack()
    fun goForward(): Boolean = sessionManager.goForward()
    fun reload() = sessionManager.reload()
    fun stop() = sessionManager.stop()
}
