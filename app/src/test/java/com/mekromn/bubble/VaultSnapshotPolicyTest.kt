package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class VaultSnapshotPolicyTest {
    @Test fun anyNonEmptySnapshotCanEnterMergeLaneAfterIndexLoad() {
        assertTrue(VaultSnapshotPolicy.accepts(true, incomingCount = 1))
        assertTrue(VaultSnapshotPolicy.accepts(true, incomingCount = 4))
        assertTrue(VaultSnapshotPolicy.accepts(true, incomingCount = 120))
    }

    @Test fun coldStartAndEmptySnapshotsAreRejected() {
        assertFalse(VaultSnapshotPolicy.accepts(false, incomingCount = 4))
        assertFalse(VaultSnapshotPolicy.accepts(false, incomingCount = 120))
        assertFalse(VaultSnapshotPolicy.accepts(true, incomingCount = 0))
        assertFalse(VaultSnapshotPolicy.accepts(true, incomingCount = -1))
    }
}
