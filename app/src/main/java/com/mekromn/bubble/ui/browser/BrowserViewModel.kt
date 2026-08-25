package com.mekromn.bubble.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mekromn.bubble.BubbleApplication
import com.mekromn.bubble.browser.engine.WebViewStateStore
import com.mekromn.bubble.browser.session.RendererPool
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.TabSessionManager
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val bubbleApplication = application as BubbleApplication
    private val stateStore = WebViewStateStore(application)
    private val rendererPool = RendererPool(application, stateStore)
    private val sessionManager = TabSessionManager(
        repository = bubbleApplication.container.tabs,
        settings = bubbleApplication.container.settings,
        rendererPool = rendererPool,
        scope = viewModelScope,
    )

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

    fun goBack(): Boolean = sessionManager.goBack()
    fun goForward(): Boolean = sessionManager.goForward()
    fun reload() = sessionManager.reload()
    fun stop() = sessionManager.stop()

    override fun onCleared() {
        rendererPool.destroyAll()
        super.onCleared()
    }
}
