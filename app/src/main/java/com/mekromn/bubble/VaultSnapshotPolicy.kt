package com.mekromn.bubble

/**
 * Snapshot admission is intentionally separate from transcript replacement.
 * Once the native index is loaded, every non-empty snapshot may enter the serialized merge lane.
 * ChatVault/VaultTranscriptMerge decides whether that snapshot extends, updates, preserves, or
 * authoritatively replaces the current branch. This prevents virtualized short tails from freezing
 * future updates while still blocking cold-start writes before the existing Vault is known.
 */
internal object VaultSnapshotPolicy {
    fun accepts(vaultLoaded: Boolean, incomingCount: Int): Boolean =
        vaultLoaded && incomingCount >= 1
}
