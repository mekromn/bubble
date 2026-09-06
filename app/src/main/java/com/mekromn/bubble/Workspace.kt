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
    var manualSuspended = false
    var forceKeepAlive = false
    var suspended = false
    var cancelledLoad = false
    var documentUrl = url
    val displayName: String get() = localName.ifBlank { title.ifBlank { "New ChatGPT chat" } }
    val recovery = RecoveryBudget()
    fun snapshot() = StoredTab(id, url, title, desktop, savedState, unread, lastNotice, localName, pinned, note, muted,
        profileId, manualSuspended, forceKeepAlive)
    companion object {
        fun restore(t: StoredTab) = ChatTab(t.id, t.url, t.title, t.desktop, t.profileId).apply {
            savedState = t.state; unread = t.unread; lastNotice = t.lastNotice
            localName = t.localName; pinned = t.pinned; note = t.note; muted = t.muted
            manualSuspended = t.manualSuspended; forceKeepAlive = t.forceKeepAlive && !t.manualSuspended
            suspended = manualSuspended
            if (manualSuspended) error = TabSuspendPolicy.MANUAL_MESSAGE
        }
    }
}

/** Main-thread session owner. Only ChatGPT has automatic idle hibernation because the exact-origin
 * monitor tells us when a response is actually working. A working/loading tab is protected; a
 * finished background chat is closed and restored from bounded session state/URL when reopened. */
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
    private fun detachTab(session: GeckoSession?) { surface.get()?.takeIf { it.session === session }?.let(::detachSurface) }
    var host = WeakReference<BrowserActivity>(null)
    private val main = Handler(Looper.getMainLooper())
    private val store = WorkspaceStore(app)
    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private var monitorSettled = false
    private val pendingStarts = LinkedHashMap<String, () -> Unit>()
    private val pendingAutoSuspends = LinkedHashMap<String, Runnable>()
    private val monitorTimeout = Runnable { finishMonitor(null) }
    private val listeners = LinkedHashSet<() -> Unit>()
    private var renderScheduled = false
    private var saveScheduled = false
    private val saveTask = Runnable { saveScheduled = false; checkpoint() }
    init {
        UploadStaging.initialize(app)
        BrowserDownloads.initialize(app)
        store.load { saved, error ->
            notice = error
            profiles += ProfilePolicy.restore(saved?.profiles ?: ProfilePolicy.defaults(), saved?.let { (it.tabs + it.closedTabs).map { tab -> tab.profileId } } ?: emptyList())
            saved?.let { state ->
                bubbleX = state.bubbleX; bubbleY = state.bubbleY; windowX = state.windowX; windowY = state.windowY
                windowWidth = state.windowWidth; windowHeight = state.windowHeight
                state.tabs.forEach { tabs += ChatTab.restore(it) }; closedTabs += state.closedTabs; selectedId = state.selected
            }
            prompts += saved?.prompts ?: StarterPrompts.items()
            if (tabs.isEmpty()) tabs += ChatTab(url = initialUrl ?: Policy.HOME)
            else if (initialUrl != null) tabs += ChatTab(url = initialUrl, profileId = selected?.profileId ?: ProfilePolicy.DEFAULT_ID).also { selectedId = it.id }
            if (selected == null) selectedId = tabs.first().id
            // Opening Bubble is an explicit reopen of the selected tab. Background idle ChatGPT
            // tabs stay cold; force-keep-alive and non-ChatGPT tabs retain live residency.
            selected?.let { resumeState(it) }
            ready = true; ensureSession(selected!!); changed(true)
            tabs.filter { it.id != selectedId }.forEachIndexed { index, tab -> main.postDelayed({
                if (tab !in tabs) return@postDelayed
                when {
                    tab.manualSuspended -> { tab.suspended = true; changed() }
                    tab.forceKeepAlive || !Policy.isChat(tab.url) -> ensureSession(tab)
                    else -> { tab.suspended = true; changed() }
                }
            }, 250L + index * 120L) }
        }
    }
    fun listen(listener: () -> Unit) { listeners += listener; listener() }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    fun changed(persist: Boolean = false) {
        checkMain()
        if (persist && ready && !saveScheduled) { saveScheduled = true; main.postDelayed(saveTask, 500) }
        if (renderScheduled || listeners.isEmpty()) return
        renderScheduled = true
        Choreographer.getInstance().postFrameCallback { renderScheduled = false; listeners.toList().forEach { it() } }
    }
    fun profileName(id: String = selected?.profileId ?: ProfilePolicy.DEFAULT_ID): String = profiles.firstOrNull { it.id == id }?.name ?: "Unknown profile"
    fun createProfile(raw: String): BrowserProfile {
        checkMain(); require(ProfilePolicy.nameProblem(raw, profiles) == null) { ProfilePolicy.nameProblem(raw, profiles).orEmpty() }
        val profile = BrowserProfile(ProfilePolicy.newId(), ProfilePolicy.name(raw)); profiles += profile; changed(true); return profile
    }
    fun renameProfile(id: String, raw: String) {
        checkMain(); val index = profiles.indexOfFirst { it.id == id }; require(index >= 0)
        require(ProfilePolicy.nameProblem(raw, profiles, id) == null) { ProfilePolicy.nameProblem(raw, profiles, id).orEmpty() }
        profiles[index] = profiles[index].copy(name = ProfilePolicy.name(raw)); changed(true)
    }
    fun create(url: String = Policy.HOME, profileId: String = selected?.profileId ?: ProfilePolicy.DEFAULT_ID): ChatTab {
        checkMain(); require(Policy.isWeb(url)); require(profiles.any { it.id == profileId }) { "Unknown storage profile" }
        val tab = ChatTab(url = url, profileId = profileId); tabs += tab; select(tab.id); return tab
    }
    fun openInProfile(id: String, profileId: String): ChatTab? {
        checkMain(); val original = tabs.firstOrNull { it.id == id } ?: return null
        if (profiles.none { it.id == profileId }) return null
        val tab = ChatTab(url = original.url, title = original.title, desktop = original.desktop, profileId = profileId)
        tabs += tab; select(tab.id); return tab
    }
    private fun resumeState(tab: ChatTab) {
        cancelAutoSuspend(tab.id)
        tab.manualSuspended = false; tab.suspended = false; tab.cancelledLoad = false
        if (tab.error == TabSuspendPolicy.MANUAL_MESSAGE) tab.error = null
    }
    fun select(id: String) {
        checkMain(); val tab = tabs.firstOrNull { it.id == id } ?: return
        resumeState(tab); selectedId = id; tab.unread = false; Replies.clear(app, id); ensureSession(tab); applyPolicy(); changed(true)
    }
    fun cycle(backwards: Boolean = false) {
        val index = QuickTabPolicy.nextIndex(tabs.indexOfFirst { it.id == selectedId }, tabs.size, backwards)
        if (index >= 0) select(tabs[index].id)
    }
    fun nextUnread(): ChatTab? {
        val current = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        for (offset in 1..tabs.size) { val tab = tabs[(current + offset) % tabs.size]; if (tab.unread) { select(tab.id); return tab } }
        return null
    }
    fun duplicate(id: String): ChatTab? {
        val original = tabs.firstOrNull { it.id == id } ?: return null
        val copy = ChatTab(url = original.url, title = original.title, desktop = original.desktop, profileId = original.profileId)
        if (original.localName.isNotEmpty()) copy.localName = QuickTabPolicy.localName(original.localName + " · copy")
        tabs += copy; select(copy.id); return copy
    }
    fun suspend(id: String): Boolean {
        checkMain(); val tab = tabs.firstOrNull { it.id == id } ?: return false
        val fileBusy = FileUi.busy && tab.id == selectedId
        if (!TabSuspendPolicy.canManualSuspend(tab.generating, tab.loading, fileBusy)) {
            notice = when {
                tab.generating -> "That tab is generating a reply, so Bubble is keeping it alive until the response finishes."
                tab.loading -> "That tab is still loading, so Bubble is keeping it alive until the page settles."
                else -> "Finish or cancel the active file flow before suspending this tab."
            }
            changed(); return false
        }
        tab.forceKeepAlive = false; tab.manualSuspended = true
        val session = tab.session
        if (session != null) hibernate(tab, session, true)
        else {
            cancelAutoSuspend(id); tab.suspended = true; tab.error = TabSuspendPolicy.MANUAL_MESSAGE; changed(true)
        }
        return true
    }
    fun setForceKeepAlive(id: String, enabled: Boolean) {
        checkMain(); val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.forceKeepAlive = enabled
        if (enabled) {
            resumeState(tab); ensureSession(tab)
        }
        applyPolicy(); changed(true)
    }
    fun close(id: String) {
        checkMain(); val tab = tabs.firstOrNull { it.id == id } ?: return
        closedTabs.removeAll { it.id == id }; closedTabs.add(0, tab.snapshot().copy(unread = false))
        while (closedTabs.size > 20) closedTabs.removeAt(closedTabs.lastIndex)
        cancelAutoSuspend(id); tab.session?.let(FloatingFileActivity::cancelForSession)
        pendingStarts.remove(id); tabs.remove(tab); detachTab(tab.session)
        tab.session?.close(); tab.session = null; Replies.clear(app, id); UploadStaging.release(app, id)
        if (tabs.isEmpty()) tabs += ChatTab(profileId = tab.profileId)
        if (selected == null) selectedId = tabs.first().id
        selected?.let(::resumeState); ensureSession(selected!!); applyPolicy(); changed(true)
    }
    fun reopen(id: String? = null): ChatTab? {
        checkMain(); val saved = (if (id == null) closedTabs.firstOrNull() else closedTabs.firstOrNull { it.id == id }) ?: return null
        closedTabs.remove(saved); tabs.firstOrNull { it.id == saved.id }?.let { select(it.id); return it }
        val tab = ChatTab.restore(saved.copy(unread = false)); tabs += tab; select(tab.id); return tab
    }
    fun rename(id: String, value: String) { tabs.firstOrNull { it.id == id }?.let { it.localName = QuickTabPolicy.localName(value); changed(true) } }
    fun setNote(id: String, value: String) { tabs.firstOrNull { it.id == id }?.let { it.note = value.take(16384); changed(true) } }
    fun togglePin(id: String) { tabs.firstOrNull { it.id == id }?.let { it.pinned = !it.pinned; changed(true) } }
    fun toggleMute(id: String) { tabs.firstOrNull { it.id == id }?.let { it.muted = !it.muted; Replies.clear(app, id); changed(true) } }
    fun savePrompt(id: String?, title: String, body: String) {
        require(title.isNotBlank() && body.isNotBlank())
        val snippet = PromptSnippet(id ?: UUID.randomUUID().toString(), QuickTabPolicy.localName(title), body.take(16384))
        val index = prompts.indexOfFirst { it.id == snippet.id }; if (index < 0) prompts.add(snippet) else prompts[index] = snippet; changed(true)
    }
    fun deletePrompt(id: String) { prompts.removeAll { it.id == id }; changed(true) }
    fun navigate(raw: String) {
        checkMain(); val url = Policy.resolve(raw)
        if (url == null) { notice = "That address scheme is not supported."; changed(); return }
        val tab = selected ?: return
        resumeState(tab); tab.url = url; tab.error = null; tab.recovery.reset(); tab.generating = false; tab.run = ""; tab.savedState = null
        val session = tab.session; if (session == null) ensureSession(tab) else loadWhenReady(tab, session, null); applyPolicy(); changed(true)
    }
    fun stopLoading(id: String = selectedId) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.cancelledLoad = true; pendingStarts.remove(id); tab.session?.stop(); tab.loading = false; tab.error = null; applyPolicy(); changed()
    }
    fun retry() {
        selected?.let { tab ->
            resumeState(tab); tab.error = null; tab.recovery.reset()
            if (tab.session == null) ensureSession(tab) else if (!pendingStarts.containsKey(tab.id)) tab.session?.reload()
        }
        applyPolicy(); changed(true)
    }
    fun desktop() {
        selected?.let { tab ->
            tab.desktop = !tab.desktop
            tab.session?.settings?.userAgentMode = if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            tab.session?.settings?.viewportMode = if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            if (!pendingStarts.containsKey(tab.id)) tab.session?.reload(); changed(true)
        }
    }
    private fun selectedVisible(tab: ChatTab): Boolean = tab.id == selectedId && (visible || floatingVisible)
    private fun cancelAutoSuspend(id: String) { pendingAutoSuspends.remove(id)?.let(main::removeCallbacks) }
    private fun scheduleAutoSuspend(tab: ChatTab, session: GeckoSession, delayMs: Long = 1800L) {
        if (tab.forceKeepAlive || tab.manualSuspended || tab.generating || tab.loading || selectedVisible(tab) || !Policy.isChat(tab.url)) {
            cancelAutoSuspend(tab.id); return
        }
        val existing = pendingAutoSuspends[tab.id]
        if (existing != null && delayMs > 0) return
        if (existing != null) { main.removeCallbacks(existing); pendingAutoSuspends.remove(tab.id) }
        val task = Runnable {
            pendingAutoSuspends.remove(tab.id)
            if (tab !in tabs || tab.session !== session || !session.isOpen) return@Runnable
            if (!TabSuspendPolicy.automatic(Policy.isChat(tab.url), selectedVisible(tab), tab.generating, tab.loading, tab.forceKeepAlive)) return@Runnable
            hibernate(tab, session, false)
        }
        pendingAutoSuspends[tab.id] = task
        if (delayMs <= 0) main.post(task) else main.postDelayed(task, delayMs)
    }
    private fun hibernate(tab: ChatTab, session: GeckoSession, manual: Boolean) {
        if (tab !in tabs || tab.session !== session) return
        cancelAutoSuspend(tab.id); FloatingFileActivity.cancelForSession(session); pendingStarts.remove(tab.id)
        detachTab(session)
        runCatching { session.setFocused(false); session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT); session.setActive(false); session.flushSessionState() }
        tab.session = null; tab.loading = false; tab.painted = false; tab.suspended = true
        if (manual) { tab.manualSuspended = true; tab.forceKeepAlive = false; tab.error = TabSuspendPolicy.MANUAL_MESSAGE }
        runCatching { session.close() }; UploadStaging.release(app, tab.id); changed(true)
    }
    fun applyPolicy() {
        checkMain()
        tabs.forEach { tab -> tab.session?.let { session ->
            if (!session.isOpen) return@let
            when {
                tab.manualSuspended -> {
                    cancelAutoSuspend(tab.id); session.setFocused(false); session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT); session.setActive(false)
                }
                tab.forceKeepAlive || !Policy.isChat(tab.url) || selectedVisible(tab) || tab.generating || tab.loading -> {
                    cancelAutoSuspend(tab.id); tab.suspended = false; session.setActive(true); session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                    session.setFocused(!FileUi.busy && chatVisible && tab.id == selectedId && surface.get()?.hasWindowFocus() == true)
                }
                else -> {
                    // Keep a short active grace period after deselection so a just-submitted
                    // response can announce "started" before we decide this ChatGPT tab is idle.
                    tab.suspended = false; session.setActive(true); session.setPriorityHint(GeckoSession.PRIORITY_HIGH); session.setFocused(false)
                    scheduleAutoSuspend(tab, session)
                }
            }
        } }
    }
    private fun engine(): GeckoRuntime {
        runtime?.let { return it }
        val created = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).consoleOutput(false).build())
        runtime = created; created.settings.setPreferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
        main.postDelayed(monitorTimeout, 10_000)
        created.webExtensionController.ensureBuiltIn("resource://android/assets/chat-monitor/", "chat-monitor@bubble.local").accept({ addon -> finishMonitor(addon) }, { finishMonitor(null) })
        return created
    }
    private fun finishMonitor(addon: WebExtension?) {
        if (addon != null) { extension = addon; liveCompatibilityReady = true; tabs.forEach { tab -> tab.session?.let { installMonitor(tab, it, addon) } } }
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
                tab.loading = false; tab.error = "This tab could not be restored (${error.javaClass.simpleName}). Its address is retained; tap Retry."; applyPolicy(); changed()
            }
        }
        pendingStarts.remove(tab.id); if (!monitorSettled) { tab.loading = true; pendingStarts[tab.id] = start } else start()
    }
    fun ensureSession(tab: ChatTab): GeckoSession? {
        checkMain(); if (tab.manualSuspended) return null
        tab.session?.let { return it }; if (tab.error != null) return null
        tab.suspended = false
        return try { val session = newSession(tab); session.open(engine()); loadWhenReady(tab, session, tab.savedState); applyPolicy(); session }
        catch (error: RuntimeException) {
            tab.error = "Browser startup failed (${error.javaClass.simpleName}). Tap Retry."; pendingStarts.remove(tab.id)
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
                cancelAutoSuspend(tab.id); FloatingFileActivity.cancelForSession(s)
                if (Policy.isWeb(url)) tab.url = url
                tab.painted = false; tab.loading = true; tab.progress = 0; tab.error = null; tab.cancelledLoad = false
                tab.suspended = false; tab.generating = false; tab.run = ""; applyPolicy(); changed(true)
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) { if (tab.session === s) { tab.progress = progress.coerceIn(0, 100); changed() } }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (tab.session !== s) return
                tab.loading = false
                if (!success && !tab.cancelledLoad && tab.error == null) tab.error = "The page did not finish loading. Check the connection or retry."
                applyPolicy(); changed(true)
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
                if (url != null && Policy.isWeb(url)) { tab.url = url; tab.documentUrl = url }
                applyPolicy(); changed(true)
            }
            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) { if (tab.session === s) { tab.back = canGoBack; changed() } }
            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) { if (tab.session === s) { tab.forward = canGoForward; changed() } }
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (Policy.isWeb(request.uri) || request.uri == "about:blank" || request.uri.startsWith("blob:")) return null
                if (request.hasUserGesture && tab.id == selectedId) {
                    if (floatingVisible) BubbleService.active?.window?.offerExternal(request.uri) else if (visible) host.get()?.offerExternal(request.uri)
                }
                return GeckoResult.deny()
            }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (tab.session !== s || tab !in tabs || (!Policy.isWeb(uri) && uri != "about:blank")) return null
                val popup = ChatTab(url = if (Policy.isWeb(uri)) uri else Policy.HOME, profileId = tab.profileId)
                tabs += popup; selectedId = popup.id; val child = newSession(popup)
                main.post { applyPolicy(); changed(true) }; return GeckoResult.fromValue(child)
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (tab.session !== s || tab.cancelledLoad) return null
                tab.loading = false; tab.error = "Page unavailable · Gecko ${error.category}/${error.code}. No certificate bypass is applied."; applyPolicy(); changed(); return null
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(s: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                if (tab.session !== s || tab !in tabs || !chatVisible || tab.id != selectedId || FileUi.busy) return GeckoResult.fromValue(prompt.dismiss())
                return if (floatingVisible) FloatingFileActivity.launch(app, tab.id, s, prompt) else host.get()?.chooseFile(prompt) ?: GeckoResult.fromValue(prompt.dismiss())
            }
        }
        extension?.let { installMonitor(tab, session, it) }
        TransferDelegates.install(app, this, tab, session)
        return session
    }
    private fun lost(tab: ChatTab, session: GeckoSession) {
        if (tab.session !== session || tab !in tabs) return
        main.post {
            if (tab.session !== session || tab !in tabs) return@post
            cancelAutoSuspend(tab.id); FloatingFileActivity.cancelForSession(session)
            detachTab(session); pendingStarts.remove(tab.id)
            tab.session = null; tab.loading = false; tab.painted = false; tab.generating = false; runCatching { session.close() }
            UploadStaging.release(app, tab.id)
            if (tab.manualSuspended || tab.suspended) {
                if (!tab.manualSuspended) tab.error = null
            } else if (tab.recovery.allow(SystemClock.elapsedRealtime())) {
                tab.error = null; main.postDelayed({ if (tab in tabs && tab.session == null && !tab.manualSuspended) ensureSession(tab) }, 500)
            } else tab.error = "This page's renderer failed repeatedly. Automatic recovery is paused; tap Retry. Your tab is retained."
            applyPolicy(); changed()
        }
    }
    private fun installMonitor(tab: ChatTab, session: GeckoSession, addon: WebExtension) {
        val blobRuntime = engine()
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                if (nativeApp != "bubble" || sender.session !== session ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    tab.session !== session || tab !in tabs) return null
                val objectMessage = message as? JSONObject ?: return null
                if (objectMessage.optString("event") == "blob-download") {
                    if (sender.isTopLevel && Policy.isWeb(sender.url)) {
                        BlobDownloads.receive(app, this@Workspace, tab, session, blobRuntime, sender.url, objectMessage)
                    }
                    return null
                }
                if (!sender.isTopLevel || !Policy.isChat(sender.url) || !Policy.isChat(tab.url)) return null
                val run = objectMessage.optString("run").takeIf { it.length in 1..128 } ?: return null
                when (objectMessage.optString("event")) {
                    "started" -> {
                        cancelAutoSuspend(tab.id); tab.suspended = false; tab.generating = true; tab.run = run; applyPolicy(); changed()
                    }
                    "aborted" -> if (tab.run == run) {
                        tab.generating = false; applyPolicy(); changed()
                    }
                    "finished" -> {
                        if (!tab.generating || tab.run != run || tab.lastNotice == run) return null
                        tab.generating = false; tab.lastNotice = run; tab.unread = !(chatVisible && tab.id == selectedId); applyPolicy(); changed()
                        checkpoint { saved ->
                            if (saved && tab in tabs && tab.unread && !tab.muted && tab.lastNotice == run) Replies.finished(app, tab.id)
                            if (tab in tabs && tab.session === session && !selectedVisible(tab) && !tab.forceKeepAlive && !tab.manualSuspended) {
                                // Finished background work has no reason to keep a renderer. Close it
                                // immediately after the durable checkpoint/notification decision.
                                scheduleAutoSuspend(tab, session, 0)
                            }
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
        store.save(StoredWorkspace(selectedId, tabs.map { it.snapshot() }, bubbleX, bubbleY, windowX, windowY, windowWidth, windowHeight, closedTabs.toList(), prompts.toList(), profiles.toList())) { saved ->
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
