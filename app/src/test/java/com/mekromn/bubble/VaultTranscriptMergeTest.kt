package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class VaultTranscriptMergeTest {
    private fun u(text: String) = VaultMessage("user", text)
    private fun a(text: String) = VaultMessage("assistant", text)

    @Test fun virtualizedTailAppendsNewTurnsWithoutDroppingOldHistory() {
        val saved = listOf(u("one"), a("two"), u("three"), a("four"))
        val incoming = listOf(u("three"), a("four"), u("five"), a("six"))
        assertEquals(listOf(u("one"), a("two"), u("three"), a("four"), u("five"), a("six")),
            VaultTranscriptMerge.merge(saved, incoming, authoritative = false))
    }

    @Test fun shorterRefreshWindowCannotShrinkVault() {
        val saved = listOf(u("one"), a("two"), u("three"), a("four"), u("five"), a("six"))
        val incoming = listOf(u("five"), a("six"))
        assertEquals(saved, VaultTranscriptMerge.merge(saved, incoming, authoritative = false))
    }

    @Test fun streamingAssistantTurnIsExtendedInPlace() {
        val saved = listOf(u("question"), a("partial answer"))
        val incoming = listOf(a("partial answer with the completed ending"))
        assertEquals(listOf(u("question"), a("partial answer with the completed ending")),
            VaultTranscriptMerge.merge(saved, incoming, authoritative = false))
    }

    @Test fun brandNewAlternatingTurnCanAppendWithoutOverlap() {
        val saved = listOf(u("one"), a("two"))
        assertEquals(listOf(u("one"), a("two"), u("three")),
            VaultTranscriptMerge.merge(saved, listOf(u("three")), authoritative = false))
    }

    @Test fun authoritativeFullHistoryCanReplaceCurrentBranch() {
        val saved = listOf(u("one"), a("old branch"), u("old followup"))
        val current = listOf(u("one"), a("regenerated branch"))
        assertEquals(current, VaultTranscriptMerge.merge(saved, current, authoritative = true))
    }
}
