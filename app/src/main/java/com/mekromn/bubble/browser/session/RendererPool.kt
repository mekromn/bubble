package com.mekromn.bubble.browser.session

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.mekromn.bubble.ai.monitor.AiChatSignalSink
import com.mekromn.bubble.browser.engine.BrowserEngineEvents
import com.mekromn.bubble.browser.engine.BrowserEngineSession
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.engine.SystemWebViewFactory
import com.mekromn.bubble.browser.engine.WebViewStateStore
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
    private val stateStore: WebViewStateStore,
    aiChatSignalSink: AiChatSignalSink,
) : BrowserEngineEvents {
    private data class Resident(
        val session: BrowserEngineSession,
        var lastUsed: Long,
        var keepRendererAlive: Boolean,
        var pinned: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val factory = SystemWebViewFactory(context.applicationContext, aiChatSignalSink)
    private val residents = LinkedHashMap<TabId, Resident>()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryPolicy = RendererMemoryPolicy(activityManager.memoryClass)

    private var memoryMode = RendererMemoryMode.BALANCED
    private var memoryPressure = RendererMemoryPressure.NORMAL

    var listener: RendererPoolListener? = null

    private val mutableActiveWebView = MutableStateFlow<WebView?>(null)
    val activeWebView: StateFlow<WebView?> = mutableActiveWebView

    private val mutableActivePageState = MutableStateFlow(EnginePageState())
    val activePageState: StateFlow<EnginePageState> = mutableActivePageState

    private var activeTabId: TabId? = null

    fun hasLiveRenderer(tabId: TabId): Boolean = residents.containsKey(tabId)
    fun liveRendererCount(): Int = residents.size

    suspend fun activate(tab: Tab): RendererActivation {
        checkMainThread()
        val existing = residents[tab.id]
        if (existing != null) {
            existing.lastUsed = now()
            existing.keepRendererAlive = tab.keepRendererAlive
            existing.pinned = tab.pinned
            existing.session.setUserAgentMode(tab.userAgentMode)
            activeTabId = tab.id
            mutableActiveWebView.value = existing.session.webView
            mutableActivePageState.value = existing.session.pageState.value
            trimWarmRenderers()
            return RendererActivation(restoredSavedState = false, reusedLiveRenderer = true)
        }

        val session = factory.create(tab, this)
        residents[tab.id] = Resident(
            session = session,
            lastUsed = now(),
            keepRendererAlive = tab.keepRendererAlive,
            pinned = tab.pinned,
        )
        activeTabId = tab.id
        mutableActiveWebView.value = session.webView
        mutableActivePageState.value = session.pageState.value

        val restored = stateStore.restore(tab.id, session.webView)
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
            trimWarmRenderers()
            return RendererActivation(restoredSavedState = false, reusedLiveRenderer = true)
        }

        val session = factory.create(tab, this)
        residents[tab.id] = Resident(
            session = session,
            lastUsed = now(),
            keepRendererAlive = tab.keepRendererAlive,
            pinned = tab.pinned,
        )
        val restored = stateStore.restore(tab.id, session.webView)
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
        if (activeTabId == tabId) clearActiveProjection()
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
        residents[tabId]?.keepRendererAlive = enabled
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
        if (activeTabId == tabId) clearActiveProjection()
        resident?.session?.destroy()
        if (discardSavedState) stateStore.delete(tabId)
    }

    suspend fun saveAndRelease(tabId: TabId): Boolean {
        checkMainThread()
        val resident = residents.remove(tabId) ?: return false
        val saved = stateStore.save(tabId, resident.session.webView)
        resident.session.destroy()
        if (activeTabId == tabId) clearActiveProjection()
        return saved
    }

    fun destroyAll() {
        checkMainThread()
        residents.values.forEach { resident -> runCatching { resident.session.destroy() } }
        residents.clear()
        clearActiveProjection()
    }

    override fun onPageState(tabId: TabId, state: EnginePageState) {
        residents[tabId]?.lastUsed = now()
        if (activeTabId == tabId) mutableActivePageState.value = state
        listener?.onPageState(tabId, state)
    }

    override fun onRendererGone(tabId: TabId, didCrash: Boolean) {
        val dead = residents.remove(tabId)
        if (activeTabId == tabId) clearActiveProjection()
        listener?.onRendererGone(tabId, didCrash)
        if (dead != null) {
            mainHandler.post { runCatching { dead.session.destroy() } }
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
            val saved = stateStore.save(candidate.key, candidate.value.session.webView)
            candidate.value.session.destroy()
            listener?.onRendererEvicted(candidate.key, saved)
        }
    }

    private fun evictableWarmCount(): Int = residents.count { (tabId, resident) ->
        tabId != activeTabId && !resident.keepRendererAlive
    }

    private fun clearActiveProjection() {
        activeTabId = null
        mutableActiveWebView.value = null
        mutableActivePageState.value = EnginePageState()
    }

    private fun activeSession(): BrowserEngineSession? = activeTabId?.let { residents[it]?.session }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RendererPool must run on the main thread" }
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
