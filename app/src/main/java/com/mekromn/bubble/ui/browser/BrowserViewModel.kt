package com.mekromn.bubble.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mekromn.bubble.BubbleApplication
import com.mekromn.bubble.ai.chatgpt.ChatGptAdapter
import com.mekromn.bubble.ai.model.WorkspaceId
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

    /** The primary plus action opens a fresh ChatGPT surface; the omnibox still allows any URL. */
    fun createTab() {
        viewModelScope.launch { sessionManager.createTab(ChatGptAdapter.TRUSTED_ORIGIN + "/") }
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

    /**
     * The browser shell keeps one minimize affordance. Ordinary web tabs retain the legacy
     * one-tab head behavior. AI-chat tabs collapse the entire provider workspace so the overlay
     * service projects one aggregate workspace bubble instead.
     */
    fun minimizeActiveToHead(onComplete: (TabId?) -> Unit) {
        viewModelScope.launch {
            val selected = sessionManager.state.value.tabs.firstOrNull { it.selected }
            if (selected == null) {
                onComplete(null)
                return@launch
            }
            val workspace = aiWorkspaces.workspaceForTab(selected.id)
            if (workspace != null) {
                aiWorkspaces.setLastActive(selected.id)
                aiWorkspaces.setCollapsed(workspace.id, true)
                onComplete(selected.id)
            } else {
                onComplete(sessionManager.minimizeSelectedToHead())
            }
        }
    }

    fun setWorkspaceCollapsed(
        workspaceId: WorkspaceId,
        collapsed: Boolean,
        onComplete: () -> Unit = {},
    ) {
        viewModelScope.launch {
            aiWorkspaces.setCollapsed(workspaceId, collapsed)
            onComplete()
        }
    }

    fun setUserAgentMode(tabId: TabId, mode: UserAgentMode) {
        viewModelScope.launch { sessionManager.setUserAgentMode(tabId, mode) }
    }

    fun setRefreshRateMode(mode: RefreshRateMode) {
        viewModelScope.launch { settingsRepository.setRefreshRateMode(mode) }
    }

    fun saveCurrentSession(name: String, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch { onSaved(sessionManager.saveCurrentSession(name)) }
    }

    fun restoreSavedSession(
        id: String,
        mode: SavedSessionRestoreMode,
        onRestored: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch { onRestored(sessionManager.restoreSavedSession(id, mode)) }
    }

    fun deleteSavedSession(id: String) {
        viewModelScope.launch { sessionManager.deleteSavedSession(id) }
    }

    fun goBack(): Boolean = sessionManager.goBack()
    fun goForward(): Boolean = sessionManager.goForward()
    fun reload() = sessionManager.reload()
    fun stop() = sessionManager.stop()
}
