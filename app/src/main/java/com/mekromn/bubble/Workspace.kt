package com.mekromn.bubble

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.lang.ref.WeakReference
import java.util.UUID
import org.mozilla.geckoview.*
import org.json.JSONObject

internal class ChatTab(val id: String = UUID.randomUUID().toString(), var url: String = Policy.HOME,
    var title: String = "New chat", var desktop: Boolean = false) {
    var session: GeckoSession? = null
    var savedState: String? = null
    var painted = false
    var loading = false
    var progress = 0
    var back = false
    var forward = false
    var error: String? = null
    var generating = false
    var unread = false
    var run = ""
    var lastNotice = ""
    val recovery = RecoveryBudget()
}

/** Main-thread session owner. No initialization from Application or Gecko child processes. */
internal class Workspace private constructor(private val app: Context, initialUrl: String?) {
    val tabs = ArrayList<ChatTab>()
    var selectedId = ""
        private set
    val selected: ChatTab? get() = tabs.firstOrNull { it.id == selectedId }
    var ready = false
        private set
    var notice: String? = null
    var bubbleX = 0.88f
    var bubbleY = 0.3f
    var visible = false
    var host = WeakReference<BrowserActivity>(null)
    private val main = Handler(Looper.getMainLooper())
    private val store = WorkspaceStore(app)
    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private var monitorSettled = false
    private val pendingStarts = LinkedHashMap<String, () -> Unit>()
    private val monitorTimeout = Runnable { finishMonitor(null) }
    private val listeners = LinkedHashSet<() -> Unit>()
    private var renderScheduled = false
    private var saveScheduled = false
    private val saveTask = Runnable { saveScheduled = false; checkpoint() }

    init {
        store.load { saved, error ->
            notice = error
            saved?.let { state ->
                bubbleX = state.bubbleX; bubbleY = state.bubbleY
                state.tabs.forEach { t -> tabs += ChatTab(t.id, t.url, t.title, t.desktop).apply {
                    savedState = t.state; unread = t.unread; lastNotice = t.lastNotice
                } }
                selectedId = state.selected
            }
            if (tabs.isEmpty()) tabs += ChatTab(url = initialUrl ?: Policy.HOME)
            else if (initialUrl != null) tabs += ChatTab(url = initialUrl).also { selectedId = it.id }
            if (selected == null) selectedId = tabs.first().id
            ready = true
            ensureSession(selected!!)
            changed(true)
            val pending = tabs.filter { it.id != selectedId && Policy.isChat(it.url) }.map { it.id }
            pending.forEachIndexed { index, id -> main.postDelayed({
                tabs.firstOrNull { it.id == id }?.let(::ensureSession)
            }, 250L + index * 120L) }
        }
    }
    fun listen(listener: () -> Unit) { listeners += listener; listener() }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    fun changed(persist: Boolean = false) {
        checkMain()
        // Coalesce into the first scheduled checkpoint, NOT a trailing debounce. A streaming
        // page that updates metadata every 250ms must not postpone saving forever.
        if (persist && ready && !saveScheduled) {
            saveScheduled = true
            main.postDelayed(saveTask, 500)
        }
        if (renderScheduled || listeners.isEmpty()) return
        renderScheduled = true
        Choreographer.getInstance().postFrameCallback {
            renderScheduled = false
            listeners.toList().forEach { it() }
        }
    }
    fun create(url: String = Policy.HOME): ChatTab {
        checkMain(); require(Policy.isWeb(url))
        val tab = ChatTab(url = url)
        tabs += tab; select(tab.id); return tab
    }
    fun select(id: String) {
        checkMain()
        val tab = tabs.firstOrNull { it.id == id } ?: return
        selectedId = id; tab.unread = false
        Replies.clear(app, id)
        ensureSession(tab)
        applyPolicy(); changed(true)
    }
    fun close(id: String) {
        checkMain()
        val tab = tabs.firstOrNull { it.id == id } ?: return
        pendingStarts.remove(id)
        tabs.remove(tab)
        host.get()?.detachSession(tab.session)
        tab.session?.close(); tab.session = null
        Replies.clear(app, id)
        if (tabs.isEmpty()) tabs += ChatTab()
        if (selected == null) selectedId = tabs.first().id
        ensureSession(selected!!); applyPolicy(); changed(true)
    }
    fun navigate(raw: String) {
        checkMain()
        val url = Policy.resolve(raw)
        if (url == null) { notice = "That address scheme is not supported."; changed(); return }
        val tab = selected ?: return
        tab.url = url; tab.error = null; tab.recovery.reset()
        tab.generating = false; tab.run = ""; tab.savedState = null
        val session = tab.session
        if (session == null) ensureSession(tab) else loadWhenReady(tab, session, null)
        changed(true)
    }
    fun retry() {
        selected?.let { tab ->
            tab.error = null; tab.recovery.reset()
            if (tab.session == null) ensureSession(tab)
            else if (!pendingStarts.containsKey(tab.id)) tab.session?.reload()
        }
        changed()
    }
    fun desktop() {
        selected?.let { tab ->
            tab.desktop = !tab.desktop
            tab.session?.settings?.userAgentMode = if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            tab.session?.settings?.viewportMode = if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            if (!pendingStarts.containsKey(tab.id)) tab.session?.reload()
            changed(true)
        }
    }
    fun applyPolicy() {
        checkMain()
        tabs.forEach { tab -> tab.session?.let { session ->
            if (!session.isOpen) return@let
            val live = Policy.isChat(tab.url) || tab.generating
            session.setActive(live || (visible && tab.id == selectedId))
            session.setFocused(visible && tab.id == selectedId)
            session.setPriorityHint(if (live || tab.id == selectedId) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        } }
    }
    private fun engine(): GeckoRuntime {
        runtime?.let { return it }
        val created = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder()
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .remoteDebuggingEnabled(false).consoleOutput(false).build())
        runtime = created
        main.postDelayed(monitorTimeout, 10_000)
        created.webExtensionController.ensureBuiltIn("resource://android/assets/chat-monitor/", "chat-monitor@bubble.local")
            .accept({ addon -> finishMonitor(addon) }, { finishMonitor(null) })
        return created
    }
    private fun finishMonitor(addon: WebExtension?) {
        if (addon != null) {
            extension = addon
            tabs.forEach { tab -> tab.session?.let { installMonitor(tab, it, addon) } }
        }
        if (monitorSettled) return
        monitorSettled = true
        main.removeCallbacks(monitorTimeout)
        if (addon == null) notice = "Reply detection is unavailable. Browsing remains available."
        val starts = pendingStarts.values.toList()
        pendingStarts.clear()
        starts.forEach { it() }
        changed()
    }
    private fun loadWhenReady(tab: ChatTab, session: GeckoSession, saved: String?) {
        val start = start@{
            if (tab !in tabs || tab.session !== session || !session.isOpen) return@start
            // This callback can execute after ensureSession's try/catch has returned. Contain
            // restoration errors HERE as well, and preserve the canonical URL as the fallback.
            try {
                val restored = saved?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
                var resumed = false
                if (restored != null) {
                    resumed = runCatching { session.restoreState(restored) }.isSuccess
                }
                if (!resumed) {
                    if (saved != null) tab.savedState = null
                    session.loadUri(tab.url)
                }
            } catch (error: RuntimeException) {
                tab.loading = false
                tab.error = "This tab could not be restored (${error.javaClass.simpleName}). Its address is retained; tap Retry."
                changed()
            }
        }
        pendingStarts.remove(tab.id)
        if (Policy.isChat(tab.url) && !monitorSettled) {
            tab.loading = true; pendingStarts[tab.id] = start
        } else start()
    }
    fun ensureSession(tab: ChatTab): GeckoSession? {
        checkMain()
        tab.session?.let { return it }
        if (tab.error != null) return null
        return try {
            val session = newSession(tab)
            session.open(engine())
            loadWhenReady(tab, session, tab.savedState)
            applyPolicy()
            session
        } catch (error: RuntimeException) {
            tab.error = "Browser startup failed (${error.javaClass.simpleName}). Tap Retry."
            pendingStarts.remove(tab.id)
            tab.session?.let { runCatching { it.close() } }; tab.session = null
            changed(); null
        }
    }
    private fun newSession(tab: ChatTab): GeckoSession {
        val session = GeckoSession(GeckoSessionSettings.Builder().allowJavascript(true)
            .contextId("normal").suspendMediaWhenInactive(false)
            .userAgentMode(if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build())
        tab.session = session
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                if (tab.session !== s) return
                if (Policy.isWeb(url)) tab.url = url
                tab.painted = false; tab.loading = true; tab.progress = 0; tab.error = null
                tab.generating = false; tab.run = ""
                changed(true)
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) {
                if (tab.session !== s) return
                tab.progress = progress.coerceIn(0, 100); changed()
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (tab.session !== s) return
                tab.loading = false
                if (!success && tab.error == null) tab.error = "The page did not finish loading. Check the connection or retry."
                changed(true)
            }
            override fun onSessionStateChange(s: GeckoSession, state: GeckoSession.SessionState) {
                if (tab.session !== s || pendingStarts.containsKey(tab.id)) return
                val value = state.toString()
                tab.savedState = value.takeIf { it.length <= 524288 }
                changed(true)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (tab.session !== s) return
                tab.title = title.orEmpty().take(512); changed(true)
            }
            override fun onFirstContentfulPaint(s: GeckoSession) {
                if (tab.session !== s) return
                tab.painted = true; changed()
            }
            override fun onCrash(s: GeckoSession) { lost(tab, s) }
            override fun onKill(s: GeckoSession) { lost(tab, s) }
            override fun onCloseRequest(s: GeckoSession) { main.post { if (tab.session === s) close(tab.id) } }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(s: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (tab.session !== s) return
                if (url != null && Policy.isWeb(url)) tab.url = url
                applyPolicy(); changed(true)
            }
            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) { if (tab.session === s) { tab.back = canGoBack; changed() } }
            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) { if (tab.session === s) { tab.forward = canGoForward; changed() } }
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (Policy.isWeb(request.uri) || request.uri == "about:blank" || request.uri.startsWith("blob:")) return null
                if (request.hasUserGesture) host.get()?.offerExternal(request.uri)
                return GeckoResult.deny()
            }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!Policy.isWeb(uri) && uri != "about:blank") return null
                val popup = ChatTab(url = if (Policy.isWeb(uri)) uri else Policy.HOME)
                tabs += popup; selectedId = popup.id
                val child = newSession(popup)
                main.post { applyPolicy(); changed(true) }
                return GeckoResult.fromValue(child)
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (tab.session !== s) return null
                tab.loading = false; tab.error = "Page unavailable · Gecko ${error.category}/${error.code}. No certificate bypass is applied."
                changed(); return null
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(s: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return if (visible && tab.id == selectedId) host.get()?.chooseFile(prompt)
                    ?: GeckoResult.fromValue(prompt.dismiss()) else GeckoResult.fromValue(prompt.dismiss())
            }
        }
        extension?.let { installMonitor(tab, session, it) }
        return session
    }
    private fun lost(tab: ChatTab, session: GeckoSession) {
        if (tab.session !== session || tab !in tabs) return
        main.post {
            if (tab.session !== session || tab !in tabs) return@post
            host.get()?.detachSession(session)
            pendingStarts.remove(tab.id)
            tab.session = null; tab.loading = false; tab.painted = false; tab.generating = false
            runCatching { session.close() }
            val needsRecovery = tab.id == selectedId || Policy.isChat(tab.url)
            if (!needsRecovery) tab.error = null
            else if (tab.recovery.allow(SystemClock.elapsedRealtime())) {
                tab.error = null
                main.postDelayed({ if (tab in tabs && tab.session == null) ensureSession(tab) }, 500)
            } else tab.error = "This page's renderer failed repeatedly. Automatic recovery is paused; tap Retry. Your tab is retained."
            changed()
        }
    }
    private fun installMonitor(tab: ChatTab, session: GeckoSession, addon: WebExtension) {
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                if (nativeApp != "bubble" || sender.session !== session || !sender.isTopLevel ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !Policy.isChat(sender.url) || !Policy.isChat(tab.url) || tab.session !== session || tab !in tabs) return null
                val objectMessage = message as? JSONObject ?: return null
                val run = objectMessage.optString("run").takeIf { it.length in 1..128 } ?: return null
                when (objectMessage.optString("event")) {
                    "started" -> { tab.generating = true; tab.run = run; changed() }
                    "aborted" -> { if (tab.run == run) { tab.generating = false; changed() } }
                    "finished" -> {
                        if (!tab.generating || tab.run != run || tab.lastNotice == run) return null
                        tab.generating = false; tab.lastNotice = run
                        tab.unread = !(visible && tab.id == selectedId)
                        changed()
                        checkpoint { saved ->
                            if (saved && tab in tabs && tab.unread && tab.lastNotice == run) Replies.finished(app, tab.id)
                        }
                    }
                }
                return null
            }
        }, "bubble")
    }
    fun checkpoint(done: ((Boolean) -> Unit)? = null) {
        if (!ready) return
        main.removeCallbacks(saveTask)
        saveScheduled = false
        store.save(StoredWorkspace(selectedId, tabs.map { StoredTab(it.id, it.url, it.title, it.desktop, it.savedState, it.unread, it.lastNotice) }, bubbleX, bubbleY)) { saved ->
            if (!saved && notice == null) { notice = "Workspace changes could not be saved. Check free storage; the previous snapshot is preserved."; changed() }
            done?.invoke(saved)
        }
    }
    fun flush() { tabs.forEach { it.session?.flushSessionState() }; checkpoint() }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        private var instance: Workspace? = null
        fun get(context: Context, initialUrl: String? = null): Workspace {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 28) check(Application.getProcessName() == context.packageName) {
                "Workspace may only be created in the main application process"
            }
            return instance ?: Workspace(context.applicationContext, initialUrl).also { instance = it }
        }
    }
}
