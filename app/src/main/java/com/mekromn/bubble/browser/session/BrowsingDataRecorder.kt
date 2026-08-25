package com.mekromn.bubble.browser.session

import com.mekromn.bubble.data.db.RoomBrowsingDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Derives durable history/recently-closed events from the authoritative logical session state.
 * UI, overlays and raw WebView callbacks never write browser history directly.
 */
class BrowsingDataRecorder(
    sessions: TabSessionManager,
    private val browsingData: RoomBrowsingDataRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            var previous: Map<TabId, Tab>? = null
            sessions.state.collect { state ->
                if (!state.initialized) return@collect
                val current = state.tabs.associateBy(Tab::id)
                val old = previous
                if (old != null) {
                    old.forEach { (id, tab) ->
                        if (id !in current) browsingData.recordClosed(tab)
                    }
                    current.forEach { (id, tab) ->
                        val prior = old[id]
                        if (prior == null || prior.lastCommittedUrl != tab.lastCommittedUrl) {
                            browsingData.recordVisit(tab)
                        }
                    }
                }
                previous = current
            }
        }
    }
}
