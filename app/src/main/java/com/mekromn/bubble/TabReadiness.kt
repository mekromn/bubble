package com.mekromn.bubble

/**
 * Compact visual state for tab switchers. Each state has a distinct identity so the conversation
 * chooser can be scanned without reading every subtitle.
 */
internal enum class TabReadiness(val label: String, val fill: Int, val edge: Int) {
    ATTENTION("Needs attention", 0x553b1114, 0xffef8b91.toInt()),
    GENERATING("Generating", 0x55402f0f, 0xffffd166.toInt()),
    LOADING("Loading", 0x55272d45, 0xffaeb8ff.toInt()),
    READY("Reply ready", 0x55203a2b, 0xff7fe0a3.toInt()),
    VOICE("Voice live", 0x55172f40, 0xff73c8ff.toInt()),
    ACTIVE("Active", 0x5520393b, 0xff8fe5df.toInt()),
    KEEP_ALIVE("Forced live", 0x551b3640, 0xff6bd5e7.toInt()),
    IDLE("Idle", 0x551f2b2e, 0xff82aaa7.toInt()),
    SUSPENDED("Suspended", 0x55202020, 0xff7d848d.toInt());

    companion object {
        fun of(tab: ChatTab, selectedVisible: Boolean = false): TabReadiness =
            TabStatusPolicy.of(tab, selectedVisible).readiness
    }
}

internal data class TabStatus(
    val readiness: TabReadiness,
    val detail: String,
    val busy: Boolean = false
)

/**
 * Turns raw Gecko/ChatGPT state into user-facing tab status. The ordering is intentional:
 * problems and active work beat unread state, unread beats suspension, and explicit keep-alive
 * beats ordinary idle/background classification.
 */
internal object TabStatusPolicy {
    fun of(tab: ChatTab, selectedVisible: Boolean): TabStatus {
        val host = Policy.host(tab.url).ifBlank { "page" }
        return when {
            Policy.isVoice(tab.url) && tab.error != null ->
                TabStatus(TabReadiness.ATTENTION, "Google Voice · connection needs attention")

            tab.error != null && !tab.manualSuspended ->
                TabStatus(TabReadiness.ATTENTION, "Needs attention · tap to inspect or retry")

            tab.generating ->
                TabStatus(TabReadiness.GENERATING, "ChatGPT is generating · protected from suspend", busy = true)

            tab.loading -> {
                val progress = tab.progress.coerceIn(0, 100)
                val phase = if (!tab.painted && progress < 15) "Starting page" else "Loading page"
                val suffix = if (progress in 1..99) " · $progress%" else ""
                TabStatus(TabReadiness.LOADING, "$phase$suffix · protected from suspend", busy = true)
            }

            Policy.isVoice(tab.url) && tab.unread ->
                TabStatus(TabReadiness.READY, "Google Voice · new alert · protected live")

            tab.unread && (tab.suspended || tab.session == null) ->
                TabStatus(TabReadiness.READY, "Reply ready · hibernated after completion · tap to open")

            tab.unread ->
                TabStatus(TabReadiness.READY, "Reply ready · unread")

            Policy.isVoice(tab.url) ->
                TabStatus(TabReadiness.VOICE, "Google Voice · protected live")

            tab.manualSuspended ->
                TabStatus(TabReadiness.SUSPENDED, "Manually suspended · tap to resume")

            tab.suspended || tab.session == null ->
                TabStatus(TabReadiness.SUSPENDED,
                    if (Policy.isChat(tab.url)) "ChatGPT hibernated · tap to resume" else "Suspended · tap to resume")

            tab.forceKeepAlive ->
                TabStatus(TabReadiness.KEEP_ALIVE, "Forced live · automatic suspend disabled")

            Policy.isChat(tab.url) && selectedVisible ->
                TabStatus(TabReadiness.ACTIVE, "ChatGPT active · ready for input")

            Policy.isChat(tab.url) ->
                TabStatus(TabReadiness.IDLE, "ChatGPT idle · eligible for 15-minute hibernation")

            selectedVisible ->
                TabStatus(TabReadiness.ACTIVE, "Active · $host")

            else ->
                TabStatus(TabReadiness.IDLE, "Live in background · $host · not auto-suspended")
        }
    }
}
