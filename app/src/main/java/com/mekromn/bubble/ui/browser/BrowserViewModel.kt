package com.mekromn.bubble.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mekromn.bubble.BubbleApplication
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val bubbleApplication = application as BubbleApplication
    private val sessionManager = bubbleApplication.runtime.sessions
    private val rendererPool = bubbleApplication.runtime.rendererPool

    val sessionState = sessionManager.state
    val activeWebView = rendererPool.activeWebView
    val pageState = rendererPool.activePageState

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
        viewModelScope.launch { sessionManager.activate(tabId) }
    }

    fun close(tabId: TabId) {
        viewModelScope.launch { sessionManager.close(tabId) }
    }

    fun moveTab(tabId: TabId, newIndex: Int) {
        viewModelScope.launch { sessionManager.moveTab(tabId, newIndex) }
    }

    fun minimizeActiveToHead(onReady: (TabId) -> Unit) {
        viewModelScope.launch {
            sessionManager.minimizeSelectedToHead()?.let(onReady)
        }
    }

    fun goBack(): Boolean = sessionManager.goBack()
    fun goForward(): Boolean = sessionManager.goForward()
    fun reload() = sessionManager.reload()
    fun stop() = sessionManager.stop()
}
