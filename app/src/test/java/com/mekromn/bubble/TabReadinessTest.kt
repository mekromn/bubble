package com.mekromn.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class TabReadinessTest {
    @Test fun priorityOrderMatchesUserVisibleReadiness() {
        val error = ChatTab().apply { error = "offline"; unread = true; generating = true }
        assertEquals(TabReadiness.ATTENTION, TabReadiness.of(error))

        val working = ChatTab().apply { generating = true; unread = true }
        assertEquals(TabReadiness.WORKING, TabReadiness.of(working))

        val ready = ChatTab().apply { unread = true; suspended = true }
        assertEquals(TabReadiness.READY, TabReadiness.of(ready))

        val voice = ChatTab(url = "https://voice.google.com/")
        assertEquals(TabReadiness.VOICE, TabReadiness.of(voice))

        val suspended = ChatTab().apply { suspended = true }
        assertEquals(TabReadiness.SUSPENDED, TabReadiness.of(suspended))

        assertEquals(TabReadiness.LIVE, TabReadiness.of(ChatTab()))
    }
}
