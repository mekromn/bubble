package com.mekromn.bubble

import android.app.Application
import com.mekromn.bubble.browser.engine.WebViewStateStore
import com.mekromn.bubble.browser.session.RendererPool
import com.mekromn.bubble.browser.session.TabSessionManager
import com.mekromn.bubble.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BubbleRuntime(
    application: Application,
    container: AppContainer,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val rendererPool = RendererPool(application, WebViewStateStore(application))
    val sessions = TabSessionManager(
        repository = container.tabs,
        settings = container.settings,
        rendererPool = rendererPool,
        scope = scope,
    )

    init {
        scope.launch { sessions.initialize() }
    }
}
