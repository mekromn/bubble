package com.mekromn.bubble

import android.app.Application
import com.mekromn.bubble.ai.adapter.AiChatAdapterRegistry
import com.mekromn.bubble.ai.chatgpt.ChatGptAdapter
import com.mekromn.bubble.ai.notifications.AiReplyNotificationCoordinator
import com.mekromn.bubble.ai.workspace.AiWorkspaceCoordinator
import com.mekromn.bubble.browser.engine.WebViewStateStore
import com.mekromn.bubble.browser.navigation.NavigationResolver
import com.mekromn.bubble.browser.session.BrowsingDataRecorder
import com.mekromn.bubble.browser.session.RendererPool
import com.mekromn.bubble.browser.session.TabSessionManager
import com.mekromn.bubble.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BubbleRuntime(
    application: Application,
    container: AppContainer,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val aiAdapters = AiChatAdapterRegistry(listOf(ChatGptAdapter()))
    val aiWorkspaces = AiWorkspaceCoordinator(
        repository = container.aiWorkspaces,
        scope = scope,
    )
    val rendererPool = RendererPool(
        context = application,
        stateStore = WebViewStateStore(application),
        aiChatSignalSink = aiWorkspaces,
    )
    val sessions = TabSessionManager(
        repository = container.tabs,
        settings = container.settings,
        headPlacements = container.headPlacements,
        savedSessionRepository = container.savedSessions,
        rendererPool = rendererPool,
        aiWorkspaces = aiWorkspaces,
        aiAdapters = aiAdapters,
        scope = scope,
    )
    val replyNotifications = AiReplyNotificationCoordinator(
        context = application,
        workspaces = aiWorkspaces,
        scope = scope,
    )
    private val browsingDataRecorder = BrowsingDataRecorder(
        sessions = sessions,
        browsingData = container.browsingData,
        scope = scope,
    )

    init {
        scope.launch {
            sessions.initialize()
            val onlyTab = sessions.state.value.tabs.singleOrNull()
            if (
                onlyTab != null &&
                onlyTab.lastCommittedUrl == NavigationResolver.NEW_TAB_URL &&
                onlyTab.title == "New tab"
            ) {
                sessions.navigate(ChatGptAdapter.TRUSTED_ORIGIN + "/")
            }
        }
        scope.launch {
            container.settings.settings
                .map { it.rendererMemoryMode }
                .distinctUntilChanged()
                .collect { rendererPool.setMemoryMode(it) }
        }
    }

    fun onTrimMemory(level: Int) {
        scope.launch { rendererPool.onTrimMemory(level) }
    }

    fun onLowMemory() {
        scope.launch { rendererPool.onLowMemory() }
    }
}
