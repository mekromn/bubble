package com.mekromn.bubble

import android.content.Context

/** Native New Chat path matching the supplied extension's continuity behavior. */
internal object ChatVaultActions {
    fun newChat(context: Context, workspace: Workspace): ChatTab {
        val current = workspace.selected
        val profileId = current?.profileId ?: ProfilePolicy.DEFAULT_ID
        if (current != null && Policy.isChat(current.url)) {
            // The page bridge continuously checkpoints rendered turns. Native New Chat therefore
            // stages the newest committed snapshot for this exact conversation/profile before the
            // new logical tab is created. If there is no saved turn yet, no unrelated chat is used.
            ChatVaultRegistry.get(context).stageForUrl(current.url, profileId)
        }
        return workspace.create(Policy.HOME, profileId)
    }
}
