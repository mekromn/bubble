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
    var title: String = "New chat", var desktop: Boolean = false,
    val profileId: String = ProfilePolicy.DEFAULT_ID) {
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
    var localName = ""
    var pinned = false
    var note = ""
    var muted = false
    var cancelledLoad = false
    val displayName: String get() = localName.ifBlank { title.ifBlank { "New ChatGPT chat" } }
    val recovery = RecoveryBudget()
    fun snapshot() = StoredTab(id, url, title, desktop, savedState, unread, lastNotice, localName, pinned, note, muted, profileId)
    companion object {
        fun restore(t: StoredTab) = ChatTab(t.id, t.url, t.title, t.desktop, t.profileId).apply {
            savedState = t.state; unread = t.unread; lastNotice = t.lastNotice
            localName = t.localName; pinned = t.pinned; note = t.note; muted = t.muted
        }
    }
}

/** Main-thread session owner. No initialization from Application or Gecko child processes. */
internal class Workspace private constructor(private val app: Context, initialUrl: String?) {
    val tabs = ArrayList<ChatTab>()
    val closedTabs = ArrayList<StoredTab>()
    val prompts = ArrayList<PromptSnippet>()
    val profiles = ArrayList<BrowserProfile>()
    var selectedId = ""
        private set
    val selected: ChatTab? get() = tabs.firstOrNull { it.id == selectedId }
    var ready = false
        private set
    var liveCompatibilityReady = false
        private set
    var notice: String? = null
    var bubbleX = 0.88f
    var bubbleY = 0.3f
    var visible = false
    var covered = false
    var quickMenuVisible = false
    var floatingVisible = false
    private var surface = WeakReference<GeckoView>(null)
    var windowX = .5f
    var windowY = .25f
    var windowWidth = .92f
    var windowHeight = .72f
    val chatVisible: Boolean get() = !quickMenuVisible && ((visible && !covered) || floatingVisible)

    /** A GeckoSession has exactly one display owner, even across Activity/service handoffs. */
    fun attachSurface(view: GeckoView, session: GeckoSession) {
        if (surface.get() === view && view.session === session) return
        surface.get()?.let { old -> if (old.session != null) old.releaseSession() }
        if (view.session != null) view.releaseSession()
        view.setSession(session); surface = WeakReference(view); applyPolicy()
    }
    fun detachSurface(view: GeckoView) {
        if (view.session != null) view.releaseSession()
        if (surface.get() === view) surface.clear()
        applyPolicy()
    }
    private fun detachTab(session: GeckoSession?) {
        surface.get()?.takeIf { it.session === session }?.let(::detachSurface)
    }
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
            profiles += ProfilePolicy.restore(saved?.profiles ?: ProfilePolicy.defaults(),
                saved?.let { (it.tabs + it.closedTabs).map { tab -> tab.profileId } } ?: emptyList())
            saved?.let { state ->
                bubbleX = state.bubbleX; bubbleY = state.bubbleY
                windowX = state.windowX; windowY = state.windowY
                windowWidth = state.windowWidth; windowHeight = state.windowHeight
                state.tabs.forEach { tabs += ChatTab.restore(it) }
                closedTabs += state.closedTabs
                selectedId = state.selected
            }
            prompts += saved?.prompts ?: StarterPrompts.items()
            if (tabs.isEmpty()) tabs += ChatTab(url = initialUrl ?: Policy.HOME)
            else if (initialUrl != null) tabs += ChatTab(url = initialUrl, profileId = selected?.profileId ?: ProfilePolicy.DEFAULT_ID).also { selectedId = it.id }
            if (selected == null) selectedId = tabs.first().id
            ready = true; ensureSession(selected!!); changed(true)
            // All logical tabs restore, paced across main-loop turns rather than one startup burst.
            tabs.filter { it.id != selectedId }.map { it.id }.forEachIndexed { index, id ->
                main.postDelayed({ tabs.firstOrNull { it.id == id }?.let(::ensureSession) }, 250L + index * 120L)
            }
        }
    }
    fun listen(listener: () -> Unit) { listeners += listener; listener() }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    fun changed(persist: Boolean = false) {
        checkMain()
        if (persist && ready && !saveScheduled) { saveScheduled = true; main.postDelayed(saveTask, 500) }
        if (renderScheduled || listeners.isEmpty()) return
        renderScheduled = true
        Choreographer.getInstance().postFrameCallback {
            renderScheduled = false; listeners.toList().forEach { it() }
        }
    }
    fun profileName(id: String = selected?.profileId ?: ProfilePolicy.DEFAULT_ID): String =
        profiles.firstOrNull { it.id == id }?.name ?: "Unknown profile"
    fun createProfile(raw: String): BrowserProfile {
        checkMain()
        require(ProfilePolicy.nameProblem(raw, profiles) == null) { ProfilePolicy.nameProblem(raw, profiles).orEmpty() }
        val profile = BrowserProfile(ProfilePolicy.newId(), ProfilePolicy.name(raw))
        profiles += profile; changed(true); return profile
    }
    fun renameProfile(id: String, raw: String) {
        checkMain()
        val index = profiles.indexOfFirst { it.id == id }
        require(index >= 0)
        require(ProfilePolicy.nameProblem(raw, profiles, id) == null) { ProfilePolicy.nameProblem(raw, profiles, id).orEmpty() }
        profiles[index] = profiles[index].copy(name = ProfilePolicy.name(raw)); changed(true)
    }
    fun create(url: String = Policy.HOME, profileId: String = selected?.profileId ?: ProfilePolicy.DEFAULT_ID): ChatTab {
        checkMain(); require(Policy.isWeb(url))
        require(profiles.any { it.id == profileId }) { "Unknown storage profile" }
        val tab = ChatTab(url = url, profileId = profileId); tabs += tab; select(tab.id); return tab
    }
    /** Open URL only. Never transfer cookies, form state, session history or generation to another profile. */
    fun openInProfile(id: String, profileId: String): ChatTab? {
        checkMain()
        val original = tabs.firstOrNull { it.id == id } ?: return null
        if (profiles.none { it.id == profileId }) return null
        val tab = ChatTab(url = original.url, title = original.title, desktop = original.desktop, profileId = profileId)
        tabs += tab; select(tab.id); return tab
    }
    fun select(id: String) {
        checkMain()
        val tab = tabs.firstOrNull { it.id == id } ?: return
        selectedId = id; tab.unread = false; Replies.clear(app, id)
        ensureSession(tab); applyPolicy(); changed(true)
    }
    fun cycle(backwards: Boolean = false) {
        val index = QuickTabPolicy.nextIndex(tabs.indexOfFirst { it.id == selectedId }, tabs.size, backwards)
        if (index >= 0) select(tabs[index].id)
    }
    fun nextUnread(): ChatTab? {
        val current = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        for (offset in 1..tabs.size) {
            val tab = tabs[(current + offset) % tabs.size]
            if (tab.unread) { select(tab.id); return tab }
        }
        return null
    }
    fun duplicate(id: String): ChatTab? {
        val original = tabs.firstOrNull { it.id == id } ?: return null
        // A separate browser tab at the same URL; not a server-side conversation branch and
        // never a copy/replay of an unsent composer or an in-flight generation.
        val copy = ChatTab(url = original.url, title = original.title, desktop = original.desktop, profileId = original.profileId)
        if (original.localName.isNotEmpty()) copy.localName = QuickTabPolicy.localName(original.localName + " · copy")
        tabs += copy; select(copy.id); return copy
    }
    fun close(id: String) {
        checkMain()
        val tab = tabs.firstOrNull { it.id == id } ?: return
        closedTabs.removeAll { it.id == id }
        closedTabs.add(0, tab.snapshot().copy(unread = false))
        while (closedTabs.size > 20) closedTabs.removeAt(closedTabs.lastIndex)
        pendingStarts.remove(id); tabs.remove(tab); detachTab(tab.session)
        tab.session?.close(); tab.session = null; Replies.clear(app, id)
        if (tabs.isEmpty()) tabs += ChatTab(profileId = tab.profileId)
        if (selected == null) selectedId = tabs.first().id
        ensureSession(selected!!); applyPolicy(); changed(true)
    }
    fun reopen(id: String? = null): ChatTab? {
        checkMain()
        val saved = (if (id == null) closedTabs.firstOrNull() else closedTabs.firstOrNull { it.id == id }) ?: return null
        closedTabs.remove(saved)
        tabs.firstOrNull { it.id == saved.id }?.let { select(it.id); return it }
        val tab = ChatTab.restore(saved.copy(unread = false)); tabs += tab; select(tab.id); return tab
    }
    fun rename(id: String, value: String) { tabs.firstOrNull { it.id == id }?.let { it.localName = QuickTabPolicy.localName(value); changed(true) } }
    fun setNote(id: String, value: String) { tabs.firstOrNull { it.id == id }?.let { it.note = value.take(16384); changed(true) } }
    fun togglePin(id: String) { tabs.firstOrNull { it.id == id }?.let { it.pinned = !it.pinned; changed(true) } }
    fun toggleMute(id: String) { tabs.firstOrNull { it.id == id }?.let { it.muted = !it.muted; Replies.clear(app, id); changed(true) } }
    fun savePrompt(id: String?, title: String, body: String) {
        require(title.isNotBlank() && body.isNotBlank())
        val snippet = PromptSnippet(id ?: UUID.randomUUID().toString(), QuickTabPolicy.localName(title), body.take(16384))
        val index = prompts.indexOfFirst { it.id == snippet.id }
        if (index < 0) prompts.add(snippet) else prompts[index] = snippet
        changed(true)
    }
    fun deletePrompt(id: String) { prompts.removeAll { it.id == id }; changed(true) }
    fun navigate(raw: String) {
        checkMain()
        val url = Policy.resolve(raw)
        if (url == null) { notice = "That address scheme is not supported."; changed(); return }
        val tab = selected ?: return
        tab.url = url; tab.error = null; tab.recovery.reset(); tab.cancelledLoad = false
        tab.generating = false; tab.run = ""; tab.savedState = null
        val session = tab.session
        if (session == null) ensureSession(tab) else loadWhenReady(tab, session, null)
        changed(true)
    }
    fun stopLoading(id: String = selectedId) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.cancelledLoad = true; pendingStarts.remove(id); tab.session?.stop()
        tab.loading = false; tab.error = null; changed()
    }
    fun retry() {
        selected?.let { tab ->
            tab.error = null; tab.cancelledLoad = false; tab.recovery.reset()
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
            session.setActive(true)
            // Native input/accessibility focus is exclusive. Page compatibility is separate.
            session.setFocused(chatVisible && tab.id == selectedId && surface.get()?.hasWindowFocus() == true)
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        } }
    }
    private fun engine(): GeckoRuntime {
        runtime?.let { return it }
        val created = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).consoleOutput(false).build())
        runtime = created
        created.settings.setPreferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
        main.postDelayed(monitorTimeout, 10_000)
        created.webExtensionController.ensureBuiltIn("resource://android/assets/chat-monitor/", "chat-monitor@bubble.local")
            .accept({ addon -> finishMonitor(addon) }, { finishMonitor(null) })
        return created
    }
    private fun finishMonitor(addon: WebExtension?) {
        if (addon != null) {
            extension = addon; liveCompatibilityReady = true
            tabs.forEach { tab -> tab.session?.let { installMonitor(tab, it, addon) } }
        }
        if (monitorSettled) return
        monitorSettled = true; main.removeCallbacks(monitorTimeout)
        if (addon == null) notice = "Page foreground compatibility and reply detection are unavailable. Native sessions remain active; browsing is available."
        val starts = pendingStarts.values.toList(); pendingStarts.clear(); starts.forEach { it() }; changed()
    }
    private fun loadWhenReady(tab: ChatTab, session: GeckoSession, saved: String?) {
        val start = start@{
            if (tab !in tabs || tab.session !== session || !session.isOpen) return@start
            try {
                val restored = saved?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
                val resumed = restored != null && runCatching { session.restoreState(restored) }.isSuccess
                if (!resumed) { if (saved != null) tab.savedState = null; session.loadUri(tab.url) }
            } catch (error: RuntimeException) {
                tab.loading = false
                tab.error = "This tab could not be restored (${error.javaClass.simpleName}). Its address is retained; tap Retry."
                changed()
            }
        }
        pendingStarts.remove(tab.id)
        // All web documents, not only ChatGPT, wait for document-start registration.
        if (!monitorSettled) { tab.loading = true; pendingStarts[tab.id] = start } else start()
    }
    fun ensureSession(tab: ChatTab): GeckoSession? {
        checkMain(); tab.session?.let { return it }
        if (tab.error != null) return null
        return try {
            val session = newSession(tab); session.open(engine())
            loadWhenReady(tab, session, tab.savedState); applyPolicy(); session
        } catch (error: RuntimeException) {
            tab.error = "Browser startup failed (${error.javaClass.simpleName}). Tap Retry."
            pendingStarts.remove(tab.id)
            tab.session?.let { runCatching { it.close() } }; tab.session = null; changed(); null
        }
    }
    private fun newSession(tab: ChatTab): GeckoSession {
        require(profiles.any { it.id == tab.profileId } && ProfilePolicy.validId(tab.profileId))
        val session = GeckoSession(GeckoSessionSettings.Builder().allowJavascript(true).contextId(tab.profileId).suspendMediaWhenInactive(false)
            .userAgentMode(if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build())
        tab.session = session
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                if (tab.session !== s) return
                if (Policy.isWeb(url)) tab.url = url
                tab.painted = false; tab.loading = true; tab.progress = 0; tab.error = null; tab.cancelledLoad = false
                tab.generating = false; tab.run = ""; changed(true)
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) { if (tab.session === s) { tab.progress = progress.coerceIn(0, 100); changed() } }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (tab.session !== s) return
                tab.loading = false
                if (!success && !tab.cancelledLoad && tab.error == null) tab.error = "The page did not finish loading. Check the connection or retry."
                changed(true)
            }
            override fun onSessionStateChange(s: GeckoSession, state: GeckoSession.SessionState) {
                if (tab.session !== s || pendingStarts.containsKey(tab.id)) return
                tab.savedState = state.toString().takeIf { it.length <= 524288 }; changed(true)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) { if (tab.session === s) { tab.title = title.orEmpty().take(512); changed(true) } }
            override fun onFirstContentfulPaint(s: GeckoSession) { if (tab.session === s) { tab.painted = true; changed() } }
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
                if (request.hasUserGesture && tab.id == selectedId) {
                    if (floatingVisible) BubbleService.active?.window?.offerExternal(request.uri)
                    else if (visible) host.get()?.offerExternal(request.uri)
                }
                return GeckoResult.deny()
            }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (tab.session !== s || tab !in tabs || (!Policy.isWeb(uri) && uri != "about:blank")) return null
                // Login popups must share the opener's container, even if another tab is selected.
                val popup = ChatTab(url = if (Policy.isWeb(uri)) uri else Policy.HOME, profileId = tab.profileId)
                tabs += popup; selectedId = popup.id
                val child = newSession(popup)
                main.post { applyPolicy(); changed(true) }
                return GeckoResult.fromValue(child)
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (tab.session !== s || tab.cancelledLoad) return null
                tab.loading = false; tab.error = "Page unavailable · Gecko ${error.category}/${error.code}. No certificate bypass is applied."
                changed(); return null
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(s: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                if (!chatVisible || tab.id != selectedId) return GeckoResult.fromValue(prompt.dismiss())
                return if (floatingVisible) FloatingFileActivity.launch(app, tab.id, s, prompt)
                else host.get()?.chooseFile(prompt) ?: GeckoResult.fromValue(prompt.dismiss())
            }
        }
        extension?.let { installMonitor(tab, session, it) }; return session
    }
    private fun lost(tab: ChatTab, session: GeckoSession) {
        if (tab.session !== session || tab !in tabs) return
        main.post {
            if (tab.session !== session || tab !in tabs) return@post
            detachTab(session); pendingStarts.remove(tab.id)
            tab.session = null; tab.loading = false; tab.painted = false; tab.generating = false
            runCatching { session.close() }
            if (tab.recovery.allow(SystemClock.elapsedRealtime())) {
                tab.error = null; main.postDelayed({ if (tab in tabs && tab.session == null) ensureSession(tab) }, 500)
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
                        tab.generating = false; tab.lastNotice = run; tab.unread = !(chatVisible && tab.id == selectedId); changed()
                        checkpoint { saved ->
                            if (saved && tab in tabs && tab.unread && !tab.muted && tab.lastNotice == run) Replies.finished(app, tab.id)
                        }
                    }
                }
                return null
            }
        }, "bubble")
    }
    fun checkpoint(done: ((Boolean) -> Unit)? = null) {
        if (!ready) return
        main.removeCallbacks(saveTask); saveScheduled = false
        store.save(StoredWorkspace(selectedId, tabs.map { it.snapshot() }, bubbleX, bubbleY, windowX, windowY, windowWidth, windowHeight,
            closedTabs.toList(), prompts.toList(), profiles.toList())) { saved ->
            if (!saved && notice == null) { notice = "Workspace changes could not be saved. Check free storage; the previous snapshot is preserved."; changed() }
            done?.invoke(saved)
        }
    }
    fun flush() { tabs.forEach { it.session?.flushSessionState() }; checkpoint() }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        private var instance: Workspace? = null
        fun peek(): Workspace? = instance
        fun get(context: Context, initialUrl: String? = null): Workspace {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 28) check(Application.getProcessName() == context.packageName) { "Workspace may only be created in the main application process" }
            return instance ?: Workspace(context.applicationContext, initialUrl).also { instance = it }
        }
    }
}
