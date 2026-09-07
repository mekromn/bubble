package com.mekromn.bubble

/**
 * Compact visual state for tab switchers. The colors are intentionally translucent so the
 * black-glass treatment remains visible while status is obvious at a glance.
 */
internal enum class TabReadiness(val label: String, val fill: Int, val edge: Int) {
    ATTENTION("Needs attention", 0x553b1114, 0xffef8b91.toInt()),
    WORKING("Working", 0x55402f0f, 0xffffd166.toInt()),
    READY("Ready", 0x55203a2b, 0xff7fe0a3.toInt()),
    VOICE("Voice live", 0x55172f40, 0xff73c8ff.toInt()),
    LIVE("Live", 0x55233037, 0xff92c7c1.toInt()),
    SUSPENDED("Suspended", 0x55202020, 0xff7d848d.toInt());

    companion object {
        fun of(tab: ChatTab): TabReadiness = when {
            tab.error != null && !tab.manualSuspended -> ATTENTION
            tab.generating || tab.loading -> WORKING
            tab.unread -> READY
            Policy.isVoice(tab.url) -> VOICE
            tab.manualSuspended || tab.suspended || tab.session == null -> SUSPENDED
            else -> LIVE
        }
    }
}
