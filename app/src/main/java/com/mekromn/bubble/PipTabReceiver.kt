package com.mekromn.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only explicit immutable PiP PendingIntents reach this non-exported receiver. */
class PipTabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val workspace = Workspace.peek() ?: return
        if (!workspace.ready) return
        val activity = workspace.host.get() ?: return
        if (!activity.isInPictureInPictureMode) return
        when (intent.action) {
            BUBBLE -> activity.collapse()
            NEXT, PREVIOUS -> {
                val size = workspace.tabs.size
                if (size == 0) return
                val index = workspace.tabs.indexOfFirst { it.id == workspace.selectedId }.coerceAtLeast(0)
                val offset = if (intent.action == NEXT) 1 else -1
                workspace.select(workspace.tabs[(index + offset + size) % size].id)
            }
        }
    }
    companion object {
        const val NEXT = "com.mekromn.bubble.PIP_NEXT"
        const val PREVIOUS = "com.mekromn.bubble.PIP_PREVIOUS"
        const val BUBBLE = "com.mekromn.bubble.PIP_BUBBLE"
    }
}
