package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class TabSuspendPolicyTest {
    @Test fun idleBackgroundChatSuspendsButWorkingAndVisibleChatsDoNot() {
        assertTrue(TabSuspendPolicy.automatic(true, false, false, false, false))
        assertFalse(TabSuspendPolicy.automatic(true, false, true, false, false))
        assertFalse(TabSuspendPolicy.automatic(true, false, false, true, false))
        assertFalse(TabSuspendPolicy.automatic(true, true, false, false, false))
        assertFalse(TabSuspendPolicy.automatic(false, false, false, false, false))
    }

    @Test fun forceKeepAliveOverridesAutomaticButManualSuspendWins() {
        assertFalse(TabSuspendPolicy.shouldSuspend(false, true, true, false, false, false))
        assertTrue(TabSuspendPolicy.shouldSuspend(true, true, true, false, false, false))
    }

    @Test fun manualSuspendRefusesInFlightWorkAndFileUi() {
        assertFalse(TabSuspendPolicy.canManualSuspend(true, false, false))
        assertFalse(TabSuspendPolicy.canManualSuspend(false, true, false))
        assertFalse(TabSuspendPolicy.canManualSuspend(false, false, true))
        assertTrue(TabSuspendPolicy.canManualSuspend(false, false, false))
    }
}
