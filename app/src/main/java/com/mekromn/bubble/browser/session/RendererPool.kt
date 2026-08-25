package com.mekromn.bubble.browser.session

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
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
) : BrowserEngineEvents {
    private data class Resident(
        val session: BrowserEngineSession,
        var lastUsed: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val factory = SystemWebViewFactory(context.applicationContext)
    private val residents = LinkedHashMap<TabId, Resident>()
    private val warmBudget = calculateWarmBudget(context)

    var listener: RendererPoolListener? = null

    private val mutableActiveWebView = MutableStateFlow<WebView?>(null)
    val activeWebView: StateFlow<WebView?> = mutableActiveWebView

    private val mutableActivePageState = MutableStateFlow(EnginePageState())
    val activePageState: StateFlow<EnginePageState> = mutableActivePageState

    private var activeTabId: TabId? = null

    fun hasLiveRenderer(tabId: TabId): Boolean = residents.containsKey(tabId)

    suspend fun activate(tab: Tab): RendererActivation {
        checkMainThread()
        val existing = residents[tab.id]
        if (existing != null) {
            existing.lastUsed = now()
            activeTabId = tab.id
            mutableActiveWebView.value = existing.session.webView
            mutableActivePageState.value = existing.session.pageState.value
            trimWarmRenderers()
            return RendererActivation(restoredSavedState = false, reusedLiveRenderer = true)
        }

        val session = factory.create(tab.id, this)
        residents[tab.id] = Resident(session, now())
        activeTabId = tab.id
        mutableActiveWebView.value = session.webView
        mutableActivePageState.value = session.pageState.value

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

    suspend fun release(tabId: TabId, discardSavedState: Boolean) {
        checkMainThread()
        val resident = residents.remove(tabId)
        if (activeTabId == tabId) {
            activeTabId = null
            mutableActiveWebView.value = null
            mutableActivePageState.value = EnginePageState()
        }
        resident?.session?.destroy()
        if (discardSavedState) stateStore.delete(tabId)
    }

    suspend fun saveAndRelease(tabId: TabId): Boolean {
        checkMainThread()
        val resident = residents.remove(tabId) ?: return false
        val saved = stateStore.save(tabId, resident.session.webView)
        resident.session.destroy()
        if (activeTabId == tabId) {
            activeTabId = null
            mutableActiveWebView.value = null
            mutableActivePageState.value = EnginePageState()
        }
        return saved
    }

    override fun onPageState(tabId: TabId, state: EnginePageState) {
        residents[tabId]?.lastUsed = now()
        if (activeTabId == tabId) mutableActivePageState.value = state
        listener?.onPageState(tabId, state)
    }

    override fun onRendererGone(tabId: TabId, didCrash: Boolean) {
        val dead = residents.remove(tabId)
        if (activeTabId == tabId) {
            activeTabId = null
            mutableActiveWebView.value = null
            mutableActivePageState.value = EnginePageState()
        }
        listener?.onRendererGone(tabId, didCrash)
        if (dead != null) {
            mainHandler.post { runCatching { dead.session.destroy() } }
        }
    }

    private suspend fun trimWarmRenderers() {
        while (residents.size > warmBudget + 1) {
            val candidate = residents.entries
                .asSequence()
                .filter { it.key != activeTabId }
                .minByOrNull { it.value.lastUsed }
                ?: return
            residents.remove(candidate.key)
            val saved = stateStore.save(candidate.key, candidate.value.session.webView)
            candidate.value.session.destroy()
            listener?.onRendererEvicted(candidate.key, saved)
        }
    }

    private fun activeSession(): BrowserEngineSession? = activeTabId?.let { residents[it]?.session }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RendererPool must run on the main thread" }
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    private fun calculateWarmBudget(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return when {
            manager.memoryClass >= 512 -> 4
            manager.memoryClass >= 256 -> 3
            manager.memoryClass >= 192 -> 2
            else -> 1
        }
    }
}
