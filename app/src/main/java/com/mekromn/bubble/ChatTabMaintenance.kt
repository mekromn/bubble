package com.mekromn.bubble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.GeckoSession

internal data class ArchiveAllChatsResult(
    val total: Int,
    val archived: Int,
    val failed: Int,
    val messages: Int
)

/**
 * Main-thread maintenance for resident ChatGPT tabs.
 *
 * Idle tabs are checked locally at 30-minute intervals. Startup establishes a baseline without
 * consuming either of the two idle checks. Two unchanged 30-minute checks end that idle epoch; no
 * more checks occur until the user actually selects that tab and later leaves it idle again.
 *
 * Generation watchdog timing is output-driven: every observed assistant-text change resets the
 * 30-minute no-output timer. A still-generating tab is refreshed only after 30 continuous minutes
 * without output, and only once for the same assistant turn.
 */
internal object ChatTabMaintenance {
    const val IDLE_CHECK_MS = 30L * 60L * 1000L
    const val GENERATION_STALL_MS = 30L * 60L * 1000L

    private data class IdleState(
        var baseline: String = "",
        var unchangedChecks: Int = 0,
        var stopped: Boolean = false,
        var startupPulsed: Boolean = false,
        var task: Runnable? = null
    )

    private data class GenerationState(
        var run: String,
        var turn: String,
        var fingerprint: String = "",
        var refreshUsed: Boolean = false,
        var task: Runnable? = null
    )

    private val main = Handler(Looper.getMainLooper())
    private val idle = LinkedHashMap<String, IdleState>()
    private val generation = LinkedHashMap<String, GenerationState>()
    private val refreshedTurn = LinkedHashMap<String, String>()
    private val unknownRefreshAt = LinkedHashMap<String, Long>()
    private var workspace: Workspace? = null
    private var previousSelected = ""
    private val workspaceListener: () -> Unit = { onWorkspaceChanged() }

    fun attach(value: Workspace) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (workspace === value) return
        workspace?.unlisten(workspaceListener)
        workspace = value
        previousSelected = value.selectedId
        value.listen(workspaceListener)
    }

    fun channelReady(tabId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        Workspace.peek()?.let(::attach)
        val ws = workspace ?: return
        val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return
        if (!isIdleBackground(ws, tab)) return
        val state = idle.getOrPut(tabId) { IdleState() }
        if (!state.startupPulsed) {
            state.startupPulsed = true
            establishBaseline(tabId, state)
        } else scheduleIdleCheck(tabId, state)
    }

    fun channelClosed(tabId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancelIdle(tabId)
    }

    fun generationStarted(tabId: String, run: String, turn: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (run.isBlank()) return
        cancelIdle(tabId)
        val stableTurn = turn.take(256)
        val used = if (stableTurn.isNotBlank()) refreshedTurn[tabId] == stableTurn
            else SystemClock.elapsedRealtime() - (unknownRefreshAt[tabId] ?: Long.MIN_VALUE) < 2L * 60L * 60L * 1000L
        val state = generation[tabId]
        if (state == null || state.run != run) {
            state?.task?.let(main::removeCallbacks)
            generation[tabId] = GenerationState(run, stableTurn, refreshUsed = used).also { scheduleWatchdog(tabId, it) }
        }
    }

    fun generationProgress(tabId: String, run: String, turn: String, fingerprint: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (run.isBlank() || fingerprint.isBlank()) return
        val stableTurn = turn.take(256)
        var state = generation[tabId]
        if (state == null || state.run != run) {
            generationStarted(tabId, run, stableTurn)
            state = generation[tabId] ?: return
        }
        if (stableTurn.isNotBlank() && stableTurn != state.turn) {
            state.turn = stableTurn
            state.refreshUsed = refreshedTurn[tabId] == stableTurn
        }
        if (state.fingerprint == fingerprint) return
        state.fingerprint = fingerprint.take(512)
        scheduleWatchdog(tabId, state, reset = true)
    }

    fun generationEnded(tabId: String, run: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val state = generation[tabId] ?: return
        if (run.isNotBlank() && state.run != run) return
        state.task?.let(main::removeCallbacks)
        generation.remove(tabId)
        onWorkspaceChanged()
    }

    fun archiveAll(workspace: Workspace, callback: (ArchiveAllChatsResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        attach(workspace)
        val targets = workspace.tabs.filter { Policy.isChat(it.url) }
        if (targets.isEmpty()) { callback(ArchiveAllChatsResult(0, 0, 0, 0)); return }
        var archived = 0
        var failed = 0
        var messages = 0
        var index = 0

        fun next() {
            if (index >= targets.size) {
                callback(ArchiveAllChatsResult(targets.size, archived, failed, messages)); return
            }
            val tab = targets[index++]
            if (tab.manualSuspended) { failed++; next(); return }
            if (tab.session == null) workspace.ensureSession(tab)
            archiveWhenReady(tab, 0) { result ->
                if (result.success) { archived++; messages += result.messages } else failed++
                next()
            }
        }
        next()
    }

    private fun archiveWhenReady(tab: ChatTab, attempt: Int, done: (ChatArchiveResult) -> Unit) {
        if (tab.session == null || !tab.session!!.isOpen) { done(ChatArchiveResult(false, reason = "Chat tab is not running")); return }
        if (ChatVaultControls.ready(tab.id)) { ChatVaultControls.archive(tab.id, done); return }
        if (attempt >= 20) { done(ChatArchiveResult(false, reason = "Chat archive bridge did not become ready")); return }
        main.postDelayed({ archiveWhenReady(tab, attempt + 1, done) }, 500L)
    }

    private fun onWorkspaceChanged() {
        val ws = workspace ?: return
        val selected = ws.selectedId
        if (selected != previousSelected) {
            idle[selected]?.let { resetIdle(selected, it) }
            val old = previousSelected
            previousSelected = selected
            if (old.isNotBlank()) {
                val oldTab = ws.tabs.firstOrNull { it.id == old }
                if (oldTab != null && isIdleBackground(ws, oldTab)) {
                    val state = idle.getOrPut(old) { IdleState() }
                    resetIdle(old, state)
                    establishBaseline(old, state)
                }
            }
        }

        val openIds = ws.tabs.mapTo(HashSet()) { it.id }
        idle.keys.filter { it !in openIds }.toList().forEach { id -> cancelIdle(id); idle.remove(id) }
        generation.keys.filter { it !in openIds }.toList().forEach { id ->
            generation.remove(id)?.task?.let(main::removeCallbacks)
            refreshedTurn.remove(id); unknownRefreshAt.remove(id)
        }

        ws.tabs.forEach { tab ->
            if (!Policy.isChat(tab.url) || tab.id == selected || tab.manualSuspended || tab.session?.isOpen != true) {
                cancelIdle(tab.id); return@forEach
            }
            if (tab.generating || tab.loading) { cancelIdle(tab.id); return@forEach }
            val state = idle.getOrPut(tab.id) { IdleState() }
            if (!state.stopped && state.task == null && ChatVaultControls.ready(tab.id)) scheduleIdleCheck(tab.id, state)
        }
    }

    private fun isIdleBackground(ws: Workspace, tab: ChatTab): Boolean =
        Policy.isChat(tab.url) && tab.id != ws.selectedId && !tab.manualSuspended &&
            !tab.generating && !tab.loading && tab.session?.isOpen == true

    private fun establishBaseline(tabId: String, state: IdleState) {
        cancelIdle(tabId)
        ChatVaultControls.heartbeat(tabId) { result ->
            val ws = workspace
            val tab = ws?.tabs?.firstOrNull { it.id == tabId }
            if (ws == null || tab == null || !isIdleBackground(ws, tab)) return@heartbeat
            if (result.success && !result.busy) state.baseline = result.fingerprint
            scheduleIdleCheck(tabId, state)
        }
    }

    private fun scheduleIdleCheck(tabId: String, state: IdleState) {
        if (state.stopped || state.task != null) return
        val task = Runnable {
            state.task = null
            val ws = workspace ?: return@Runnable
            val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return@Runnable
            if (!isIdleBackground(ws, tab)) return@Runnable
            ChatVaultControls.heartbeat(tabId) { result ->
                val current = workspace?.tabs?.firstOrNull { it.id == tabId }
                val currentWs = workspace
                if (currentWs == null || current == null || !isIdleBackground(currentWs, current)) return@heartbeat
                if (!result.success || result.busy) { scheduleIdleCheck(tabId, state); return@heartbeat }
                if (state.baseline.isBlank() || result.fingerprint != state.baseline) {
                    state.baseline = result.fingerprint
                    state.unchangedChecks = 0
                    scheduleIdleCheck(tabId, state)
                    return@heartbeat
                }
                state.unchangedChecks++
                if (state.unchangedChecks >= 2) state.stopped = true else scheduleIdleCheck(tabId, state)
            }
        }
        state.task = task
        main.postDelayed(task, IDLE_CHECK_MS)
    }

    private fun resetIdle(tabId: String, state: IdleState) {
        cancelIdle(tabId)
        state.baseline = ""
        state.unchangedChecks = 0
        state.stopped = false
    }

    private fun cancelIdle(tabId: String) {
        idle[tabId]?.task?.let(main::removeCallbacks)
        idle[tabId]?.task = null
    }

    private fun scheduleWatchdog(tabId: String, state: GenerationState, reset: Boolean = false) {
        if (reset) state.task?.let(main::removeCallbacks)
        if (state.task != null && !reset) return
        val run = state.run
        val task = Runnable {
            state.task = null
            val ws = workspace ?: Workspace.peek() ?: return@Runnable
            val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return@Runnable
            val session = tab.session ?: return@Runnable
            if (!session.isOpen || !tab.generating || tab.run != run || state.refreshUsed) return@Runnable
            state.refreshUsed = true
            if (state.turn.isNotBlank()) refreshedTurn[tabId] = state.turn
            else unknownRefreshAt[tabId] = SystemClock.elapsedRealtime()
            runCatching { session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) }
        }
        state.task = task
        main.postDelayed(task, GENERATION_STALL_MS)
    }
}
