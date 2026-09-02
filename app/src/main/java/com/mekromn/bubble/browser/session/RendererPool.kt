package com.mekromn.bubble.browser.session

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.mekromn.bubble.browser.engine.BrowserEngineEvents
import com.mekromn.bubble.browser.engine.BrowserEngineSession
import com.mekromn.bubble.browser.engine.BrowserSessionStateStore
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.engine.GeckoBrowserFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface RendererPoolListener {
    fun onPageState(tabId: TabId, state: EnginePageState)
    fun onRendererGone(tabId: TabId, didCrash: Boolean)
    fun onRendererEvicted(tabId: TabId, stateSaved: Boolean)
}

data class RendererActivation(
    val restoredSavedState: Boolean,
    val reusedLiveRenderer: Boolean,
)

class RendererPool(
    context: Context,
    private val stateStore: BrowserSessionStateStore,
) : BrowserEngineEvents {
    private data class Resident(
        val session: BrowserEngineSession,
        var lastUsed: Long,
        var keepRendererAlive: Boolean,
        var pinned: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val factory = GeckoBrowserFactory(context.applicationContext)
    private val residents = LinkedHashMap<TabId, Resident>()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryPolicy = RendererMemoryPolicy(activityManager.memoryClass)

    private var memoryMode = RendererMemoryMode.BALANCED
    private var memoryPressure = RendererMemoryPressure.NORMAL
    private var hostContext: Context? = null

    var listener: RendererPoolListener? = null
    var newTabHandler: ((String) -> Unit)? = null

    private val mutableActiveWebView = MutableStateFlow<View?>(null)
    val activeWebView: StateFlow<View?> = mutableActiveWebView

    private val mutableActivePageState = MutableStateFlow(EnginePageState())
    val activePageState: StateFlow<EnginePageState> = mutableActivePageState

    private var activeTabId: TabId? = null

    fun hasLiveRenderer(tabId: TabId): Boolean = residents.containsKey(tabId)
    fun liveRendererCount(): Int = residents.size

    /**
     * Bind the foreground Activity that is actually capable of hosting a Gecko compositor.
     * The GeckoSession remains process-scoped; only the GeckoView is created here.
     */
    fun attachHost(context: Context) {
        checkMainThread()
        if (hostContext === context && mutableActiveWebView.value != null) return

        if (hostContext !== context) {
            activeSession()?.releaseContentView()
        }
        hostContext = context

        activeResident()?.let { resident ->
            resident.session.setLifecycle(
                active = true,
                focused = true,
                highPriority = resident.keepRendererAlive || resident.pinned,
            )
            mutableActiveWebView.value = resident.session.createContentView(context)
            mutableActivePageState.value = resident.session.pageState.value
        }
    }

    /**
     * Release the Activity-owned GeckoView without closing the durable GeckoSession. ChatGPT
     * keep-live sessions remain active/high-priority while Bubble is behind another Android app.
     */
    fun detachHost(context: Context) {
        checkMainThread()
        if (hostContext !== context) return

        activeResident()?.let { resident ->
            resident.session.releaseContentView()
            resident.session.setLifecycle(
                active = resident.keepRendererAlive,
                focused = false,
                highPriority = resident.keepRendererAlive || resident.pinned,
            )
        }
        mutableActiveWebView.value = null
        hostContext = null
    }

    suspend fun activate(tab: Tab): RendererActivation {
        checkMainThread()
        demoteCurrentIfNeeded(tab.id)
        val existing = residents[tab.id]
        if (existing != null) {
            existing.lastUsed = now()
            existing.keepRendererAlive = tab.keepRendererAlive
            existing.pinned = tab.pinned
            existing.session.setUserAgentMode(tab.userAgentMode)
            existing.session.setLifecycle(
                active = true,
                focused = hostContext != null,
                highPriority = tab.keepRendererAlive || tab.pinned,
            )
            activeTabId = tab.id
            projectActiveView(existing.session)
            mutableActivePageState.value = existing.session.pageState.value
            trimWarmRenderers()
            return RendererActivation(restoredSavedState = false, reusedLiveRenderer = true)
        }

        val session = factory.create(tab, this)
        val resident = Resident(
            session = session,
            lastUsed = now(),
            keepRendererAlive = tab.keepRendererAlive,
            pinned = tab.pinned,
        )
        residents[tab.id] = resident
        activeTabId = tab.id
        session.setLifecycle(
            active = true,
            focused = hostContext != null,
            highPriority = tab.keepRendererAlive || tab.pinned,
        )
        projectActiveView(session)
        mutableActivePageState.value = session.pageState.value

        val restored = stateStore.restore(tab.id)?.let(session::restoreSerializedState) ?: false
        if (!restored) session.loadUrl(tab.lastCommittedUrl)
        trimWarmRenderers()
        return RendererActivation(restoredSavedState = restored, reusedLiveRenderer = false)
    }

    suspend fun warm(tab: Tab): RendererActivation {
        checkMainThread()
        val existing = residents[tab.id]
        if (existing != null) {
            existing.lastUsed = now()
            existing.keepRendererAlive = tab.keepRendererAlive
            existing.pinned = tab.pinned
            existing.session.setUserAgentMode(tab.userAgentMode)
            applyBackgroundLifecycle(existing)
            trimWarmRenderers()
            return RendererActivation(restoredSavedState = false, reusedLiveRenderer = true)
        }

        val session = factory.create(tab, this)
        val resident = Resident(
            session = session,
            lastUsed = now(),
            keepRendererAlive = tab.keepRendererAlive,
            pinned = tab.pinned,
        )
        residents[tab.id] = resident
        applyBackgroundLifecycle(resident)
        val restored = stateStore.restore(tab.id)?.let(session::restoreSerializedState) ?: false
        if (!restored) session.loadUrl(tab.lastCommittedUrl)
        trimWarmRenderers()
        return RendererActivation(restoredSavedState = restored, reusedLiveRenderer = false)
    }

    fun goBack(): Boolean = activeSession()?.goBack() ?: false
    fun goForward(): Boolean = activeSession()?.goForward() ?: false
    fun reload() = activeSession()?.reload()
    fun stop() = activeSession()?.stop()
    fun loadUrl(url: String) = activeSession()?.loadUrl(url)

    fun deactivate(tabId: TabId) {
        checkMainThread()
        val resident = residents[tabId] ?: return
        resident.session.releaseContentView()
        resident.session.setLifecycle(
            active = resident.keepRendererAlive,
            focused = false,
            highPriority = resident.keepRendererAlive || resident.pinned,
        )
        if (activeTabId == tabId) clearActiveProjection(releaseView = false)
    }

    fun setUserAgentMode(tabId: TabId, mode: UserAgentMode) {
        checkMainThread()
        residents[tabId]?.session?.let { session ->
            session.setUserAgentMode(mode)
            session.reload()
        }
    }

    suspend fun setKeepRendererAlive(tabId: TabId, enabled: Boolean) {
        checkMainThread()
        residents[tabId]?.let { resident ->
            resident.keepRendererAlive = enabled
            resident.session.setLifecycle(
                active = tabId == activeTabId || enabled,
                focused = tabId == activeTabId && hostContext != null,
                highPriority = enabled || resident.pinned,
            )
        }
        if (!enabled) trimWarmRenderers()
    }

    suspend fun setMemoryMode(mode: RendererMemoryMode) {
        checkMainThread()
        if (memoryMode == mode) return
        memoryMode = mode
        stateStore.setTotalBudgetBytes(memoryPolicy.snapshotBudgetBytes(memoryMode, memoryPressure))
        trimWarmRenderers()
    }

    suspend fun onTrimMemory(level: Int) {
        applyMemoryPressure(RendererMemoryPolicy.pressureForTrimLevel(level))
    }

    suspend fun onLowMemory() {
        applyMemoryPressure(RendererMemoryPressure.CRITICAL)
    }

    private suspend fun applyMemoryPressure(pressure: RendererMemoryPressure) {
        checkMainThread()
        memoryPressure = pressure
        stateStore.setTotalBudgetBytes(memoryPolicy.snapshotBudgetBytes(memoryMode, memoryPressure))
        trimWarmRenderers()
    }

    suspend fun release(tabId: TabId, discardSavedState: Boolean) {
        checkMainThread()
        val resident = residents.remove(tabId)
        if (activeTabId == tabId) clearActiveProjection(releaseView = false)
        resident?.session?.destroy()
        if (discardSavedState) stateStore.delete(tabId)
    }

    suspend fun saveAndRelease(tabId: TabId): Boolean {
        checkMainThread()
        val resident = residents.remove(tabId) ?: return false
        resident.session.setLifecycle(active = false, focused = false, highPriority = false)
        val saved = stateStore.save(tabId, resident.session.serializedState())
        resident.session.destroy()
        if (activeTabId == tabId) clearActiveProjection(releaseView = false)
        return saved
    }

    fun destroyAll() {
        checkMainThread()
        residents.values.forEach { resident -> runCatching { resident.session.destroy() } }
        residents.clear()
        clearActiveProjection(releaseView = false)
        hostContext = null
    }

    override fun onPageState(tabId: TabId, state: EnginePageState) {
        residents[tabId]?.lastUsed = now()
        if (activeTabId == tabId) mutableActivePageState.value = state
        listener?.onPageState(tabId, state)
    }

    override fun onRendererGone(tabId: TabId, didCrash: Boolean) {
        val dead = residents.remove(tabId)
        if (activeTabId == tabId) clearActiveProjection(releaseView = false)
        listener?.onRendererGone(tabId, didCrash)
        if (dead != null) mainHandler.post { runCatching { dead.session.destroy() } }
    }

    override fun onOpenNewTab(tabId: TabId, url: String) {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            newTabHandler?.invoke(url)
        }
    }

    private suspend fun trimWarmRenderers() {
        val budget = memoryPolicy.warmBudget(memoryMode, memoryPressure)
        while (evictableWarmCount() > budget) {
            val candidate = residents.entries
                .asSequence()
                .filter { it.key != activeTabId && !it.value.keepRendererAlive }
                .minWithOrNull(
                    compareBy<Map.Entry<TabId, Resident>>(
                        { if (it.value.pinned) 1 else 0 },
                        { it.value.lastUsed },
                    ),
                )
                ?: return
            residents.remove(candidate.key)
            candidate.value.session.setLifecycle(active = false, focused = false, highPriority = false)
            val saved = stateStore.save(candidate.key, candidate.value.session.serializedState())
            candidate.value.session.destroy()
            listener?.onRendererEvicted(candidate.key, saved)
        }
    }

    private fun evictableWarmCount(): Int = residents.count { (tabId, resident) ->
        tabId != activeTabId && !resident.keepRendererAlive
    }

    private fun demoteCurrentIfNeeded(nextTabId: TabId) {
        val currentId = activeTabId ?: return
        if (currentId == nextTabId) return
        residents[currentId]?.let { resident ->
            resident.session.releaseContentView()
            applyBackgroundLifecycle(resident)
        }
        mutableActiveWebView.value = null
    }

    private fun applyBackgroundLifecycle(resident: Resident) {
        resident.session.setLifecycle(
            active = resident.keepRendererAlive,
            focused = false,
            highPriority = resident.keepRendererAlive || resident.pinned,
        )
    }

    private fun projectActiveView(session: BrowserEngineSession) {
        mutableActiveWebView.value = hostContext?.let(session::createContentView)
    }

    private fun clearActiveProjection(releaseView: Boolean = true) {
        if (releaseView) activeSession()?.releaseContentView()
        activeTabId = null
        mutableActiveWebView.value = null
        mutableActivePageState.value = EnginePageState()
    }

    private fun activeResident(): Resident? = activeTabId?.let(residents::get)
    private fun activeSession(): BrowserEngineSession? = activeResident()?.session

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RendererPool must run on the main thread" }
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
