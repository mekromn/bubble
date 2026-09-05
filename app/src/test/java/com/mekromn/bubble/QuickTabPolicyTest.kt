package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class QuickTabPolicyTest {
    @Test fun cyclingWrapsAndEmptyWorkspacesAreSafe() {
        assertEquals(0, QuickTabPolicy.nextIndex(2, 3, false))
        assertEquals(2, QuickTabPolicy.nextIndex(0, 3, true))
        assertEquals(0, QuickTabPolicy.nextIndex(0, 1, true))
        assertEquals(-1, QuickTabPolicy.nextIndex(0, 0, false))
    }
    @Test fun filtersRemainIndependent() {
        assertTrue(QuickTabPolicy.accepts(TabFilter.ALL, false, false, false))
        assertTrue(QuickTabPolicy.accepts(TabFilter.PINNED, false, false, true))
        assertFalse(QuickTabPolicy.accepts(TabFilter.PINNED, true, true, false))
        assertTrue(QuickTabPolicy.accepts(TabFilter.UNREAD, true, false, false))
        assertFalse(QuickTabPolicy.accepts(TabFilter.UNREAD, false, true, true))
        assertTrue(QuickTabPolicy.accepts(TabFilter.GENERATING, false, true, false))
    }
    @Test fun localNamesAreSingleLineAndBounded() {
        assertEquals("Name here", QuickTabPolicy.localName("  Name\nhere  "))
        assertEquals("", QuickTabPolicy.localName(" \n "))
        assertEquals(120, QuickTabPolicy.localName("x".repeat(400)).length)
    }
    @Test fun starterTemplatesHaveUniqueStableIdsAndUsefulBodies() {
        val prompts = StarterPrompts.items()
        assertEquals(12, prompts.size)
        assertEquals(prompts.size, prompts.map { it.id }.toSet().size)
        assertTrue(prompts.all { it.title.isNotBlank() && it.body.length > 60 })
        assertEquals(prompts, StarterPrompts.items())
    }
}
