package com.mekromn.bubble.browser.session

import com.mekromn.bubble.ai.adapter.AiChatAdapterRegistry
import com.mekromn.bubble.ai.workspace.AiWorkspaceCoordinator
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.navigation.NavigationResolver
import com.mekromn.bubble.browser.navigation.ResolvedNavigation
import com.mekromn.bubble.data.db.HeadPlacement
import com.mekromn.bubble.data.db.HeadPlacementRepository
import com.mekromn.bubble.data.db.SavedSessionRepository
import com.mekromn.bubble.data.db.SavedSessionRestoreMode
import com.mekromn.bubble.data.db.SavedSessionSnapshot
import com.mekromn.bubble.data.db.SavedSessionTabSnapshot
import com.mekromn.bubble.data.db.TabRepository
import com.mekromn.bubble.data.settings.BrowserSettingsRepository
import java.util.UUID
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
    private val headPlacements: HeadPlacementRepository,
    private val savedSessionRepository: SavedSessionRepository,
    private val rendererPool: RendererPool,
    private val aiWorkspaces: AiWorkspaceCoordinator,
    private val aiAdapters: AiChatAdapterRegistry,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : RendererPoolListener {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BrowserSessionState())
    val state: StateFlow<BrowserSessionState> = mutableState
    val savedSessions = savedSessionRepository.observeSummaries()

    init {
        rendererPool.listener = this
    }

    suspend fun initialize() {
        aiWorkspaces.initialize()
        mutex.withLock {
            if (mutableState.value.initialized) return@withLock
            val loaded = repository.loadAll()
            val reconstructed = if (loaded.isEmpty()) {
                listOf(newTabRecord(sortIndex = 0L, selected = true))
            } else {
                reconstructAfterProcessStart(loaded)
            }
            val prepared = reconstructed.map(::applyAiLivePolicyAfterProcessStart)
            repository.applyChanges(prepared)
            syncAiMemberships(prepared)
            publish(prepared)
            activateSelectedRenderer(prepared)
            val warmed = warmKeepAliveTabs(prepared)
            if (warmed !== prepared) publish(warmed)
        }
    }

    suspend fun createTab(url: String = NavigationResolver.NEW_TAB_URL): TabId {
        return mutex.withLock {
            val now = clock()
            val current = mutableState.value.tabs
            val demoted = current.map { tab ->
                if (tab.selected) {
                    tab.copy(
                        selected = false,
                        residencyState = if (tab.residencyState == ResidencyState.ACTIVE) {
                            ResidencyState.WARM
                        } else {
                            tab.residencyState
                        },
                    )
                } else {
                    tab
                }
            }
            val newTab = newTabRecord(
                sortIndex = (demoted.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                selected = true,
                url = url,
                now = now,
            ).copy(residencyState = ResidencyState.RECOVERING)
            val updated = demoted + newTab
            repository.applyChanges(updated)
            syncAiMembership(newTab)
            publish(updated)
            rendererPool.activate(newTab)
            markRecoveredLocked(newTab.id)
            if (isAiTab(newTab)) aiWorkspaces.setLastActive(newTab.id)
            newTab.id
        }
    }

    suspend fun createHead(url: String): TabId {
        return mutex.withLock {
            val current = mutableState.value.tabs
            val newTab = newTabRecord(
                sortIndex = (current.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                selected = false,
                url = url,
            ).copy(
                presentationState = PresentationState.HEAD,
                residencyState = if (aiAdapters.match(url) != null) {
                    ResidencyState.RECOVERING
                } else {
                    ResidencyState.HIBERNATED
                },
            )
            val updated = current + newTab
            repository.applyChanges(listOf(newTab))
            syncAiMembership(newTab)
            publish(updated)
            if (newTab.keepRendererAlive) {
                rendererPool.warm(newTab)
                markWarmLocked(newTab.id)
            }
            newTab.id
        }
    }

    suspend fun duplicateTab(tabId: TabId): TabId {
        val source = state.value.tabs.firstOrNull { it.id == tabId } ?: return createTab()
        return createTab(source.lastCommittedUrl)
    }

    suspend fun duplicateAsHead(tabId: TabId): TabId? {
        return mutex.withLock {
            val current = mutableState.value.tabs
            val source = current.firstOrNull { it.id == tabId } ?: return@withLock null
            val copy = newTabRecord(
                sortIndex = (current.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                selected = false,
                url = source.lastCommittedUrl,
            ).copy(
                title = source.title,
                presentationState = PresentationState.HEAD,
                residencyState = if (isAiTab(source)) ResidencyState.RECOVERING else ResidencyState.HIBERNATED,
                pinned = source.pinned,
                keepRendererAlive = source.keepRendererAlive || isAiTab(source),
                userAgentMode = source.userAgentMode,
                zoomPercent = source.zoomPercent,
            )
            repository.applyChanges(listOf(copy))
            syncAiMembership(copy)
            publish(current + copy)
            if (copy.keepRendererAlive) {
                rendererPool.warm(copy)
                markWarmLocked(copy.id)
            }
            copy.id
        }
    }

    suspend fun activate(tabId: TabId) {
        mutex.withLock {
            val tabs = mutableState.value.tabs
            val target = tabs.firstOrNull { it.id == tabId } ?: return@withLock
            if (target.selected && rendererPool.hasLiveRenderer(tabId)) {
                if (isAiTab(target)) aiWorkspaces.setLastActive(tabId)
                return@withLock
            }

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
                    tab.selected -> {
                        tab.copy(
                            selected = false,
                            residencyState = if (tab.residencyState == ResidencyState.ACTIVE) {
                                ResidencyState.WARM
                            } else {
                                tab.residencyState
                            },
                        )
                    }
                    else -> tab
                }
            }
            repository.applyChanges(updated)
            publish(updated)
            val active = updated.first { it.id == tabId }
            rendererPool.activate(active)
            if (active.residencyState == ResidencyState.RECOVERING) markRecoveredLocked(tabId)
            if (isAiTab(active)) aiWorkspaces.setLastActive(tabId)
        }
    }

    suspend fun minimizeSelectedToHead(): TabId? {
        val selected = state.value.selectedTabId ?: return null
        return minimizeToHead(selected)
    }

    suspend fun minimizeToHead(tabId: TabId): TabId? {
        return mutex.withLock {
            val tabs = mutableState.value.tabs
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index < 0) return@withLock null
            val target = tabs[index]
            if (target.presentationState == PresentationState.HEAD) return@withLock target.id

            val headResidency = if (target.keepRendererAlive && rendererPool.hasLiveRenderer(tabId)) {
                rendererPool.setKeepRendererAlive(tabId, true)
                rendererPool.deactivate(tabId)
                ResidencyState.WARM
            } else {
                val saved = rendererPool.saveAndRelease(tabId)
                if (saved) ResidencyState.SAVED else ResidencyState.HIBERNATED
            }

            val mutable = tabs.toMutableList()
            mutable[index] = target.copy(
                selected = false,
                presentationState = PresentationState.HEAD,
                residencyState = headResidency,
            )

            var replacement = mutable
                .filter { it.id != tabId && it.presentationState == PresentationState.BROWSER }
                .maxByOrNull(Tab::lastActivatedAt)

            if (replacement == null) {
                replacement = newTabRecord(
                    sortIndex = (mutable.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                    selected = true,
                ).copy(residencyState = ResidencyState.RECOVERING)
                mutable += replacement
            } else {
                val replacementIndex = mutable.indexOfFirst { it.id == replacement.id }
                replacement = replacement.copy(
                    selected = true,
                    residencyState = if (rendererPool.hasLiveRenderer(replacement.id)) {
                        ResidencyState.ACTIVE
                    } else {
                        ResidencyState.RECOVERING
                    },
                    lastActivatedAt = clock(),
                )
                mutable[replacementIndex] = replacement
            }

            repository.applyChanges(mutable)
            publish(mutable)
            rendererPool.activate(replacement)
            if (replacement.residencyState == ResidencyState.RECOVERING) {
                markRecoveredLocked(replacement.id)
            }
            if (isAiTab(replacement)) aiWorkspaces.setLastActive(replacement.id)
            tabId
        }
    }

    suspend fun setPinned(tabId: TabId, pinned: Boolean) {
        mutex.withLock {
            val tabs = mutableState.value.tabs
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index < 0) return@withLock
            val updatedTab = TabStateMachine.reduce(tabs[index], TabEvent.SetPinned(pinned))
            val updated = tabs.toMutableList().also { it[index] = updatedTab }
            repository.applyChanges(listOf(updatedTab))
            publish(updated)
        }
    }

    suspend fun setKeepRendererAlive(tabId: TabId, enabled: Boolean) {
        mutex.withLock {
            val tabs = mutableState.value.tabs
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index < 0) return@withLock
            val source = tabs[index]
            var updatedTab = source.copy(keepRendererAlive = enabled)
            rendererPool.setKeepRendererAlive(tabId, enabled)

            if (enabled && !source.selected) {
                if (!rendererPool.hasLiveRenderer(tabId)) rendererPool.warm(updatedTab)
                updatedTab = updatedTab.copy(residencyState = ResidencyState.WARM)
            } else if (!enabled && source.presentationState == PresentationState.HEAD && rendererPool.hasLiveRenderer(tabId)) {
                val saved = rendererPool.saveAndRelease(tabId)
                updatedTab = updatedTab.copy(
                    residencyState = if (saved) ResidencyState.SAVED else ResidencyState.HIBERNATED,
                )
            }

            val updated = tabs.toMutableList().also { it[index] = updatedTab }
            repository.applyChanges(listOf(updatedTab))
            publish(updated)
        }
    }

    suspend fun setUserAgentMode(tabId: TabId, mode: UserAgentMode) {
        mutex.withLock {
            val tabs = mutableState.value.tabs
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index < 0) return@withLock
            val source = tabs[index]
            if (source.userAgentMode == mode) return@withLock
            val updatedTab = source.copy(userAgentMode = mode)
            val updated = tabs.toMutableList().also { it[index] = updatedTab }
            repository.applyChanges(listOf(updatedTab))
            publish(updated)
            rendererPool.setUserAgentMode(tabId, mode)
        }
    }

    suspend fun saveCurrentSession(name: String): String {
        return mutex.withLock {
            val now = clock()
            val id = UUID.randomUUID().toString()
            val normalizedName = name.trim().take(80).ifBlank { "Saved session" }
            val snapshots = mutableListOf<SavedSessionTabSnapshot>()
            for (tab in mutableState.value.tabs.filterNot(Tab::isPrivate).sortedBy(Tab::sortIndex)) {
                val placement = if (tab.presentationState == PresentationState.HEAD) {
                    headPlacements.get(tab.id)
                } else {
                    null
                }
                snapshots += SavedSessionTabSnapshot(
                    lastCommittedUrl = tab.lastCommittedUrl,
                    title = tab.title,
                    presentationState = tab.presentationState,
                    pinned = tab.pinned,
                    keepRendererAlive = tab.keepRendererAlive,
                    userAgentMode = tab.userAgentMode,
                    zoomPercent = tab.zoomPercent,
                    groupKey = tab.groupId,
                    normalizedHeadX = placement?.normalizedX,
                    normalizedHeadY = placement?.normalizedY,
                )
            }
            savedSessionRepository.save(
                SavedSessionSnapshot(
                    id = id,
                    name = normalizedName,
                    createdAt = now,
                    updatedAt = now,
                    tabs = snapshots,
                ),
            )
            id
        }
    }

    suspend fun restoreSavedSession(id: String, mode: SavedSessionRestoreMode): Boolean {
        val snapshot = savedSessionRepository.load(id) ?: return false
        return mutex.withLock {
            val current = mutableState.value.tabs
            val replacing = mode == SavedSessionRestoreMode.REPLACE
            if (replacing) {
                for (tab in current) rendererPool.release(tab.id, discardSavedState = true)
            }

            val base = if (replacing) mutableListOf() else current.toMutableList()
            val existingKeys = if (mode == SavedSessionRestoreMode.MERGE) {
                base.mapTo(mutableSetOf()) { tab -> tab.lastCommittedUrl.trim() to tab.presentationState }
            } else {
                mutableSetOf()
            }
            val groupRemap = mutableMapOf<String, String>()
            val restoredPairs = mutableListOf<Pair<Tab, SavedSessionTabSnapshot>>()
            var nextSort = (base.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L
            val now = clock()

            for ((offset, savedTab) in snapshot.tabs.withIndex()) {
                val key = savedTab.lastCommittedUrl.trim() to savedTab.presentationState
                if (mode == SavedSessionRestoreMode.MERGE && key in existingKeys) continue
                existingKeys += key
                val groupId = savedTab.groupKey?.let { old ->
                    groupRemap.getOrPut(old) { UUID.randomUUID().toString() }
                }
                var restored = Tab(
                    id = TabId.newId(),
                    createdAt = now + offset,
                    lastActivatedAt = now,
                    sortIndex = nextSort++,
                    lastCommittedUrl = savedTab.lastCommittedUrl,
                    title = savedTab.title,
                    presentationState = savedTab.presentationState,
                    residencyState = if (savedTab.keepRendererAlive) ResidencyState.RECOVERING else ResidencyState.HIBERNATED,
                    pinned = savedTab.pinned,
                    keepRendererAlive = savedTab.keepRendererAlive,
                    userAgentMode = savedTab.userAgentMode,
                    zoomPercent = savedTab.zoomPercent,
                    groupId = groupId,
                    selected = false,
                )
                if (isAiTab(restored)) {
                    restored = restored.copy(
                        keepRendererAlive = true,
                        residencyState = ResidencyState.RECOVERING,
                    )
                }
                base += restored
                restoredPairs += restored to savedTab
            }

            var updated = ensureSelectedBrowserTab(base)
            val deletes = if (replacing) current.map(Tab::id) else emptyList()
            repository.applyChanges(updated, deletes = deletes)
            if (replacing) current.forEach { aiWorkspaces.removeMembership(it.id) }
            syncAiMemberships(updated)

            for ((restored, savedTab) in restoredPairs) {
                if (
                    restored.presentationState == PresentationState.HEAD &&
                    savedTab.normalizedHeadX != null &&
                    savedTab.normalizedHeadY != null
                ) {
                    headPlacements.save(
                        HeadPlacement(
                            tabId = restored.id,
                            normalizedX = savedTab.normalizedHeadX,
                            normalizedY = savedTab.normalizedHeadY,
                            displayId = null,
                            updatedAt = now,
                        ),
                    )
                }
            }

            publish(updated)
            activateSelectedRenderer(updated)
            updated = warmKeepAliveTabs(updated)
            publish(updated)
            true
        }
    }

    suspend fun deleteSavedSession(id: String) {
        savedSessionRepository.delete(id)
    }

    suspend fun close(tabId: TabId) {
        mutex.withLock {
            val current = mutableState.value.tabs
            val index = current.indexOfFirst { it.id == tabId }
            if (index < 0) return@withLock
            val closingWasSelected = current[index].selected
            rendererPool.release(tabId, discardSavedState = true)
            aiWorkspaces.removeMembership(tabId)

            val remaining = current.filterNot { it.id == tabId }.toMutableList()
            if (remaining.none { it.presentationState == PresentationState.BROWSER }) {
                remaining += newTabRecord(
                    sortIndex = (remaining.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                    selected = true,
                ).copy(residencyState = ResidencyState.RECOVERING)
            } else if (closingWasSelected) {
                val replacement = remaining
                    .filter { it.presentationState == PresentationState.BROWSER }
                    .maxByOrNull(Tab::lastActivatedAt)
                if (replacement != null) {
                    for (i in remaining.indices) {
                        val tab = remaining[i]
                        remaining[i] = if (tab.id == replacement.id) {
                            tab.copy(selected = true, residencyState = ResidencyState.RECOVERING)
                        } else {
                            tab.copy(selected = false)
                        }
                    }
                }
            }
            val normalized = remaining.mapIndexed { order, tab -> tab.copy(sortIndex = order.toLong()) }
            repository.applyChanges(normalized, deletes = listOf(tabId))
            publish(normalized)
            if (closingWasSelected) {
                activateSelectedRenderer(normalized)
                normalized.firstOrNull(Tab::selected)?.takeIf(::isAiTab)?.let { aiWorkspaces.setLastActive(it.id) }
            }
        }
    }

    suspend fun moveTab(tabId: TabId, newIndex: Int) {
        mutex.withLock {
            val mutable = mutableState.value.tabs.toMutableList()
            val oldIndex = mutable.indexOfFirst { it.id == tabId }
            if (oldIndex < 0) return@withLock
            val item = mutable.removeAt(oldIndex)
            mutable.add(newIndex.coerceIn(0, mutable.size), item)
            val normalized = mutable.mapIndexed { index, tab -> tab.copy(sortIndex = index.toLong()) }
            repository.applyChanges(normalized)
            publish(normalized)
        }
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
                val adapter = if (old.isPrivate) null else aiAdapters.match(newUrl)
                val shouldKeepLive = adapter != null
                if (
                    newUrl == old.lastCommittedUrl &&
                    newTitle == old.title &&
                    (!shouldKeepLive || old.keepRendererAlive)
                ) {
                    if (adapter != null) {
                        aiWorkspaces.ensureMembership(old, adapter)
                        if (old.selected) aiWorkspaces.setLastActive(tabId)
                    }
                    return@withLock
                }

                val updatedTab = old.copy(
                    lastCommittedUrl = newUrl,
                    title = newTitle,
                    keepRendererAlive = old.keepRendererAlive || shouldKeepLive,
                )
                val updated = tabs.toMutableList().also { it[index] = updatedTab }
                repository.applyChanges(listOf(updatedTab))
                if (shouldKeepLive) rendererPool.setKeepRendererAlive(tabId, true)
                if (adapter != null) {
                    aiWorkspaces.ensureMembership(updatedTab, adapter)
                    if (updatedTab.selected) aiWorkspaces.setLastActive(tabId)
                }
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
                when {
                    recovering.selected && recovering.presentationState == PresentationState.BROWSER -> {
                        rendererPool.activate(recovering)
                        markRecoveredLocked(tabId)
                    }
                    recovering.keepRendererAlive -> {
                        rendererPool.warm(recovering)
                        val warm = recovering.copy(residencyState = ResidencyState.WARM)
                        updated[index] = warm
                        repository.applyChanges(listOf(warm))
                        publish(updated)
                    }
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
                val source = tabs[index]
                if (source.keepRendererAlive) return@withLock
                val target = if (stateSaved) ResidencyState.SAVED else ResidencyState.HIBERNATED
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
        val selected = tabs.firstOrNull {
            it.selected && it.presentationState == PresentationState.BROWSER
        } ?: return
        rendererPool.activate(selected)
        if (selected.residencyState == ResidencyState.RECOVERING) markRecoveredLocked(selected.id)
        if (isAiTab(selected)) aiWorkspaces.setLastActive(selected.id)
    }

    private suspend fun warmKeepAliveTabs(tabs: List<Tab>): List<Tab> {
        val mutable = tabs.toMutableList()
        var changed = false
        for (index in mutable.indices) {
            val tab = mutable[index]
            if (tab.selected || !tab.keepRendererAlive) continue
            rendererPool.warm(tab)
            val warm = if (tab.residencyState == ResidencyState.WARM) tab else tab.copy(residencyState = ResidencyState.WARM)
            if (warm != tab) {
                mutable[index] = warm
                repository.applyChanges(listOf(warm))
                changed = true
            }
        }
        return if (changed) mutable else tabs
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

    private suspend fun markWarmLocked(tabId: TabId) {
        val tabs = mutableState.value.tabs
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val source = tabs[index]
        val warm = source.copy(residencyState = ResidencyState.WARM)
        val updated = tabs.toMutableList().also { it[index] = warm }
        repository.applyChanges(listOf(warm))
        publish(updated)
    }

    private fun ensureSelectedBrowserTab(input: MutableList<Tab>): List<Tab> {
        val browserTabs = input.filter { it.presentationState == PresentationState.BROWSER }
        var selectedId = browserTabs.firstOrNull(Tab::selected)?.id
            ?: browserTabs.maxByOrNull(Tab::lastActivatedAt)?.id

        if (selectedId == null) {
            val newTab = newTabRecord(
                sortIndex = (input.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                selected = true,
            ).copy(residencyState = ResidencyState.RECOVERING)
            input += newTab
            selectedId = newTab.id
        }

        return input.map { tab ->
            val selected = tab.id == selectedId
            when {
                selected && rendererPool.hasLiveRenderer(tab.id) -> tab.copy(selected = true, residencyState = ResidencyState.ACTIVE)
                selected -> tab.copy(selected = true, residencyState = ResidencyState.RECOVERING)
                tab.selected -> tab.copy(selected = false)
                else -> tab
            }
        }
    }

    private fun reconstructAfterProcessStart(loaded: List<Tab>): List<Tab> {
        val browserTabs = loaded.filter { it.presentationState == PresentationState.BROWSER }
        val selected = browserTabs.filter(Tab::selected).maxByOrNull(Tab::lastActivatedAt)
            ?: browserTabs.maxByOrNull(Tab::lastActivatedAt)
        val base = loaded.sortedBy(Tab::sortIndex).map { tab ->
            val selectedNow = selected != null && tab.id == selected.id
            val residency = when {
                selectedNow -> ResidencyState.RECOVERING
                tab.keepRendererAlive -> ResidencyState.RECOVERING
                tab.residencyState == ResidencyState.SAVED -> ResidencyState.SAVED
                else -> ResidencyState.HIBERNATED
            }
            tab.copy(selected = selectedNow, residencyState = residency)
        }.toMutableList()

        if (selected == null) {
            base += newTabRecord(
                sortIndex = (base.maxOfOrNull(Tab::sortIndex) ?: -1L) + 1L,
                selected = true,
            ).copy(residencyState = ResidencyState.RECOVERING)
        }
        return base
    }

    private fun newTabRecord(
        sortIndex: Long,
        selected: Boolean,
        url: String = NavigationResolver.NEW_TAB_URL,
        now: Long = clock(),
    ): Tab {
        val keepAiLive = aiAdapters.match(url) != null
        return Tab(
            id = TabId.newId(),
            createdAt = now,
            lastActivatedAt = now,
            sortIndex = sortIndex,
            lastCommittedUrl = url,
            selected = selected,
            keepRendererAlive = keepAiLive,
            residencyState = when {
                selected -> ResidencyState.RECOVERING
                keepAiLive -> ResidencyState.RECOVERING
                else -> ResidencyState.HIBERNATED
            },
        )
    }

    private fun applyAiLivePolicyAfterProcessStart(tab: Tab): Tab {
        if (!isAiTab(tab)) return tab
        return tab.copy(
            keepRendererAlive = true,
            residencyState = ResidencyState.RECOVERING,
        )
    }

    private fun isAiTab(tab: Tab): Boolean = !tab.isPrivate && aiAdapters.match(tab.lastCommittedUrl) != null

    private suspend fun syncAiMembership(tab: Tab) {
        if (tab.isPrivate) return
        aiAdapters.match(tab.lastCommittedUrl)?.let { adapter -> aiWorkspaces.ensureMembership(tab, adapter) }
    }

    private suspend fun syncAiMemberships(tabs: List<Tab>) {
        tabs.forEach { syncAiMembership(it) }
    }

    private fun publish(tabs: List<Tab>) {
        mutableState.value = BrowserSessionState(
            initialized = true,
            tabs = tabs.sortedBy(Tab::sortIndex),
            selectedTabId = tabs.firstOrNull(Tab::selected)?.id,
            navigationError = mutableState.value.navigationError,
        )
    }
}
