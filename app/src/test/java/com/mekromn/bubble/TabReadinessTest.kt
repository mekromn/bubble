package com.mekromn.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabReadinessTest {
    @Test fun priorityOrderMatchesUserVisibleReadiness() {
        val error = ChatTab().apply { error = "offline"; unread = true; generating = true }
        assertEquals(TabReadiness.ATTENTION, TabReadiness.of(error))

        val generating = ChatTab().apply { this.generating = true; unread = true }
        val generatingStatus = TabStatusPolicy.of(generating, selectedVisible = false)
        assertEquals(TabReadiness.GENERATING, generatingStatus.readiness)
        assertTrue(generatingStatus.busy)

        val loading = ChatTab().apply { this.loading = true; painted = true; progress = 61 }
        val loadingStatus = TabStatusPolicy.of(loading, selectedVisible = false)
        assertEquals(TabReadiness.LOADING, loadingStatus.readiness)
        assertTrue(loadingStatus.detail.contains("61%"))
        assertTrue(loadingStatus.busy)

        val ready = ChatTab().apply { unread = true; suspended = true }
        val readyStatus = TabStatusPolicy.of(ready, selectedVisible = false)
        assertEquals(TabReadiness.READY, readyStatus.readiness)
        assertTrue(readyStatus.detail.contains("hibernated"))

        val voice = ChatTab(url = "https://voice.google.com/")
        assertEquals(TabReadiness.VOICE, TabReadiness.of(voice))

        val forced = ChatTab().apply { forceKeepAlive = true }
        assertEquals(TabReadiness.KEEP_ALIVE, TabStatusPolicy.of(forced, selectedVisible = false, resident = true).readiness)

        val suspended = ChatTab().apply { suspended = true }
        assertEquals(TabReadiness.SUSPENDED, TabReadiness.of(suspended))
    }

    @Test fun chatIdleStatusKnowsWhetherItIsActuallyVisible() {
        val chat = ChatTab()
        val active = TabStatusPolicy.of(chat, selectedVisible = true, resident = true)
        val background = TabStatusPolicy.of(chat, selectedVisible = false, resident = true)
        assertEquals(TabReadiness.ACTIVE, active.readiness)
        assertEquals(TabReadiness.IDLE, background.readiness)
        assertTrue(background.detail.contains("15-minute"))
        assertFalse(active.busy)
    }

    @Test fun everyReadinessStateHasDistinctVisualIdentity() {
        val fills = TabReadiness.entries.map { it.fill }.toSet()
        val edges = TabReadiness.entries.map { it.edge }.toSet()
        assertEquals(TabReadiness.entries.size, fills.size)
        assertEquals(TabReadiness.entries.size, edges.size)
        assertNotEquals(TabReadiness.READY.edge, TabReadiness.GENERATING.edge)
        assertNotEquals(TabReadiness.GENERATING.edge, TabReadiness.ATTENTION.edge)
        assertNotEquals(TabReadiness.LOADING.edge, TabReadiness.GENERATING.edge)
    }
}
