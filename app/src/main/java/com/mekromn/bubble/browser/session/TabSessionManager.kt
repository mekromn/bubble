package com.mekromn.bubble.browser.session

import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.navigation.NavigationResolver
import com.mekromn.bubble.browser.navigation.ResolvedNavigation
import com.mekromn.bubble.data.db.TabRepository
import com.mekromn.bubble.data.settings.BrowserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrowserSessionState(
    val initialized: Boolean = false,
    val tabs: List<Tab> = emptyList(),
    val selectedTabId: TabId? = null,
    val navigationError: String? = null,
)

class TabSessionManager(
    private val repository: TabRepository,
    private val settings: BrowserSettingsRepository,
    private val rendererPool: RendererPool,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : RendererPoolListener {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BrowserSessionState())
    val state: StateFlow<BrowserSessionState> = mutableState

    init {
        rendererPool.listener = this
    }

    suspend fun initialize() = mutex.withLock {
        if (mutableState.value.initialized) return
        val loaded = repository.loadAll()
        val reconstructed = if (loaded.isEmpty()) {
            listOf(newTabRecord(sortIndex = 0L, selected = true))
        } else {
            reconstructAfterProcessStart(loaded)
        }
        repository.applyChanges(reconstructed)
        publish(reconstructed)
        activateSelectedRenderer(reconstructed)
    }

    suspend fun createTab(url: String = NavigationResolver.NEW_TAB_URL): TabId = mutex.withLock {
        val now = clock()
        val current = mutableState.value.tabs
        val demoted = current.map { tab ->
            if (tab.selected) tab.copy(
                selected = false,
                residencyState = if (tab.residencyState == ResidencyState.ACTIVE) {
                    ResidencyState.WARM
                } else {
                    tab.residencyState
                },
            ) else tab
        }
        val newTab = newTabRecord(
            sortIndex = (demoted.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
            selected = true,
            url = url,
            now = now,
        ).copy(residencyState = ResidencyState.RECOVERING)
        val updated = demoted + newTab
        repository.applyChanges(updated)
        publish(updated)
        rendererPool.activate(newTab)
        markRecoveredLocked(newTab.id)
        newTab.id
    }

    suspend fun duplicateTab(tabId: TabId): TabId {
        val source = state.value.tabs.firstOrNull { it.id == tabId } ?: return createTab()
        return createTab(source.lastCommittedUrl)
    }

    suspend fun activate(tabId: TabId) = mutex.withLock {
        val tabs = mutableState.value.tabs
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        if (target.selected && rendererPool.hasLiveRenderer(tabId)) return

        val now = clock()
        val updated = tabs.map { tab ->
            when {
                tab.id == tabId -> {
                    val residency = if (rendererPool.hasLiveRenderer(tabId)) {
                        ResidencyState.ACTIVE
                    } else {
                        ResidencyState.RECOVERING
                    }
                    tab.copy(
                        selected = true,
                        presentationState = PresentationState.BROWSER,
                        residencyState = residency,
                        lastActivatedAt = now,
                    )
                }
                tab.selected -> tab.copy(
                    selected = false,
                    residencyState = if (tab.residencyState == ResidencyState.ACTIVE) {
                        ResidencyState.WARM
                    } else tab.residencyState,
                )
                else -> tab
            }
        }
        repository.applyChanges(updated)
        publish(updated)
        val active = updated.first { it.id == tabId }
        rendererPool.activate(active)
        if (active.residencyState == ResidencyState.RECOVERING) markRecoveredLocked(tabId)
    }

    suspend fun close(tabId: TabId) = mutex.withLock {
        val current = mutableState.value.tabs
        val index = current.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val closingWasSelected = current[index].selected
        rendererPool.release(tabId, discardSavedState = true)

        val remaining = current.filterNot { it.id == tabId }.toMutableList()
        if (remaining.isEmpty()) {
            remaining += newTabRecord(sortIndex = 0L, selected = true)
        } else if (closingWasSelected) {
            val replacementIndex = index.coerceAtMost(remaining.lastIndex)
            val replacementId = remaining[replacementIndex].id
            for (i in remaining.indices) {
                val tab = remaining[i]
                remaining[i] = if (tab.id == replacementId) {
                    tab.copy(selected = true, residencyState = ResidencyState.RECOVERING)
                } else {
                    tab.copy(selected = false)
                }
            }
        }
        val normalized = remaining.mapIndexed { order, tab -> tab.copy(sortIndex = order.toLong()) }
        repository.applyChanges(normalized, deletes = listOf(tabId))
        publish(normalized)
        if (closingWasSelected) activateSelectedRenderer(normalized)
    }

    suspend fun moveTab(tabId: TabId, newIndex: Int) = mutex.withLock {
        val mutable = mutableState.value.tabs.toMutableList()
        val oldIndex = mutable.indexOfFirst { it.id == tabId }
        if (oldIndex < 0) return
        val item = mutable.removeAt(oldIndex)
        mutable.add(newIndex.coerceIn(0, mutable.size), item)
        val normalized = mutable.mapIndexed { index, tab -> tab.copy(sortIndex = index.toLong()) }
        repository.applyChanges(normalized)
        publish(normalized)
    }

    suspend fun navigate(rawInput: String) {
        val engine = settings.settings.first().searchEngine
        when (val resolved = NavigationResolver.resolve(rawInput, engine)) {
            is ResolvedNavigation.Web -> {
                mutableState.value = mutableState.value.copy(navigationError = null)
                rendererPool.loadUrl(resolved.url)
            }
            is ResolvedNavigation.UnsupportedScheme -> {
                mutableState.value = mutableState.value.copy(
                    navigationError = "Unsupported scheme: ${resolved.scheme}",
                )
            }
        }
    }

    fun goBack(): Boolean = rendererPool.goBack()
    fun goForward(): Boolean = rendererPool.goForward()
    fun reload() = rendererPool.reload()
    fun stop() = rendererPool.stop()

    override fun onPageState(tabId: TabId, state: EnginePageState) {
        scope.launch {
            mutex.withLock {
                val tabs = mutableState.value.tabs
                val index = tabs.indexOfFirst { it.id == tabId }
                if (index < 0) return@withLock
                val old = tabs[index]
                val newUrl = state.url.ifBlank { old.lastCommittedUrl }
                val newTitle = state.title.ifBlank { old.title }
                if (newUrl == old.lastCommittedUrl && newTitle == old.title) return@withLock
                val updatedTab = old.copy(lastCommittedUrl = newUrl, title = newTitle)
                val updated = tabs.toMutableList().also { it[index] = updatedTab }
                repository.applyChanges(listOf(updatedTab))
                publish(updated)
            }
        }
    }

    override fun onRendererGone(tabId: TabId, didCrash: Boolean) {
        scope.launch {
            mutex.withLock {
                val tabs = mutableState.value.tabs
                val index = tabs.indexOfFirst { it.id == tabId }
                if (index < 0) return@withLock
                val recovering = TabStateMachine.reduce(tabs[index], TabEvent.RendererGone)
                val updated = tabs.toMutableList().also { it[index] = recovering }
                repository.applyChanges(listOf(recovering))
                publish(updated)
                if (recovering.selected && recovering.presentationState == PresentationState.BROWSER) {
                    rendererPool.activate(recovering)
                    markRecoveredLocked(tabId)
                }
            }
        }
    }

    override fun onRendererEvicted(tabId: TabId, stateSaved: Boolean) {
        scope.launch {
            mutex.withLock {
                val tabs = mutableState.value.tabs
                val index = tabs.indexOfFirst { it.id == tabId }
                if (index < 0) return@withLock
                val target = if (stateSaved) ResidencyState.SAVED else ResidencyState.HIBERNATED
                val source = tabs[index]
                val updatedTab = runCatching {
                    TabStateMachine.reduce(source, TabEvent.SetResidency(target))
                }.getOrElse { source.copy(residencyState = target) }
                val updated = tabs.toMutableList().also { it[index] = updatedTab }
                repository.applyChanges(listOf(updatedTab))
                publish(updated)
            }
        }
    }

    private suspend fun activateSelectedRenderer(tabs: List<Tab>) {
        val selected = tabs.firstOrNull(Tab::selected) ?: return
        rendererPool.activate(selected)
        markRecoveredLocked(selected.id)
    }

    private suspend fun markRecoveredLocked(tabId: TabId) {
        val tabs = mutableState.value.tabs
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val source = tabs[index]
        val recovered = when (source.residencyState) {
            ResidencyState.RECOVERING -> TabStateMachine.reduce(source, TabEvent.RendererRecovered)
            ResidencyState.ACTIVE -> source
            else -> source.copy(residencyState = ResidencyState.ACTIVE)
        }
        val updated = tabs.toMutableList().also { it[index] = recovered }
        repository.applyChanges(listOf(recovered))
        publish(updated)
    }

    private fun reconstructAfterProcessStart(loaded: List<Tab>): List<Tab> {
        val selected = loaded.filter(Tab::selected).maxByOrNull(Tab::lastActivatedAt)
            ?: loaded.maxByOrNull(Tab::lastActivatedAt)
            ?: return emptyList()
        return loaded.sortedBy(Tab::sortIndex).map { tab ->
            val selectedNow = tab.id == selected.id
            tab.copy(
                selected = selectedNow,
                residencyState = if (selectedNow) {
                    ResidencyState.RECOVERING
                } else if (tab.restoreStateKey != null || tab.residencyState == ResidencyState.SAVED) {
                    ResidencyState.SAVED
                } else {
                    ResidencyState.HIBERNATED
                },
            )
        }
    }

    private fun newTabRecord(
        sortIndex: Long,
        selected: Boolean,
        url: String = NavigationResolver.NEW_TAB_URL,
        now: Long = clock(),
    ): Tab = Tab(
        id = TabId.newId(),
        createdAt = now,
        lastActivatedAt = now,
        sortIndex = sortIndex,
        lastCommittedUrl = url,
        selected = selected,
        residencyState = if (selected) ResidencyState.RECOVERING else ResidencyState.HIBERNATED,
    )

    private fun publish(tabs: List<Tab>) {
        mutableState.value = BrowserSessionState(
            initialized = true,
            tabs = tabs.sortedBy(Tab::sortIndex),
            selectedTabId = tabs.firstOrNull(Tab::selected)?.id,
            navigationError = mutableState.value.navigationError,
        )
    }
}
