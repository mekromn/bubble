package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class ProfilePolicyTest {
    @Test fun legacyContextNeverChangesAndNewIdsDoNotUseDisplayNames() {
        assertEquals("normal", ProfilePolicy.DEFAULT_ID)
        val ids = (1..100).map { ProfilePolicy.newId() }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all(ProfilePolicy::validId))
        assertFalse(ids.contains(ProfilePolicy.DEFAULT_ID))
        listOf("", "Work", "NORMAL", "../normal", "bubble-profile-not-a-uuid").forEach { assertFalse(ProfilePolicy.validId(it)) }
    }
    @Test fun profileNamesAreHumanLabelsNotStoragePaths() {
        val profiles = listOf(BrowserProfile(ProfilePolicy.DEFAULT_ID, "Default"))
        assertNotNull(ProfilePolicy.nameProblem(" default ", profiles))
        assertNotNull(ProfilePolicy.nameProblem("\n\t", profiles))
        assertNull(ProfilePolicy.nameProblem("Work", profiles))
        assertNull(ProfilePolicy.nameProblem("Default", profiles, ProfilePolicy.DEFAULT_ID))
        assertEquals("Work", ProfilePolicy.name("\n Work\t"))
    }
    @Test fun missingProfileMetadataPreservesItsExactIsolationBoundary() {
        val id = ProfilePolicy.newId()
        val result = ProfilePolicy.restore(emptyList(), listOf(id))
        assertTrue(result.any { it.id == "normal" })
        assertEquals(id, result.single { it.id != "normal" }.id)
    }
    @Test(expected = IllegalArgumentException::class)
    fun malformedContainerNeverFallsBackToDefaultAccount() {
        ProfilePolicy.restore(ProfilePolicy.defaults(), listOf("invalid-context"))
    }
}
