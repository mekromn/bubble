package com.mekromn.bubble

/**
 * ChatGPT may expose only a virtualized tail after refresh. The local Vault is cumulative: a page
 * snapshot is allowed to replace an entry only after the Vault index is loaded and only when it is
 * at least as complete as the saved copy. Passive full-history capture supplies the larger snapshot.
 */
internal object VaultSnapshotPolicy {
    fun accepts(vaultLoaded: Boolean, savedCount: Int, incomingCount: Int): Boolean =
        vaultLoaded && incomingCount >= 1 && incomingCount >= savedCount.coerceAtLeast(0)
}
