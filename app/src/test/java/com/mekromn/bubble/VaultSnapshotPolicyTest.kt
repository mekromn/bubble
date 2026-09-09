package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class VaultSnapshotPolicyTest {
    @Test fun partialRefreshCannotReplaceLargerSavedChat() {
        assertFalse(VaultSnapshotPolicy.accepts(true, savedCount = 120, incomingCount = 4))
        assertFalse(VaultSnapshotPolicy.accepts(true, savedCount = 120, incomingCount = 119))
    }

    @Test fun equalOrMoreCompleteSnapshotCanUpdateVault() {
        assertTrue(VaultSnapshotPolicy.accepts(true, savedCount = 120, incomingCount = 120))
        assertTrue(VaultSnapshotPolicy.accepts(true, savedCount = 120, incomingCount = 121))
        assertTrue(VaultSnapshotPolicy.accepts(true, savedCount = 0, incomingCount = 1))
    }

    @Test fun coldStartDoesNotWriteBeforeExistingIndexIsKnown() {
        assertFalse(VaultSnapshotPolicy.accepts(false, savedCount = 0, incomingCount = 4))
        assertFalse(VaultSnapshotPolicy.accepts(false, savedCount = 120, incomingCount = 120))
        assertFalse(VaultSnapshotPolicy.accepts(true, savedCount = 0, incomingCount = 0))
    }
}
