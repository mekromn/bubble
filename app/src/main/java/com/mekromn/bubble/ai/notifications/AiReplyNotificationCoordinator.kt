package com.mekromn.bubble.ai.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mekromn.bubble.BrowserActivity
import com.mekromn.bubble.ai.model.AiWorkspaceState
import com.mekromn.bubble.ai.workspace.AiWorkspaceCoordinator
import com.mekromn.bubble.browser.session.TabId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AiReplyNotificationCoordinator(
    context: Context,
    private val workspaces: AiWorkspaceCoordinator,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    init {
        ensureChannel()
        scope.launch { workspaces.state.collect { state -> postOutstanding(state) } }
    }

    fun refresh() {
        scope.launch { postOutstanding(workspaces.state.value) }
    }

    private suspend fun postOutstanding(state: AiWorkspaceState) {
        if (!state.initialized || !canPostNotifications()) return
        mutex.withLock {
            for (workspace in state.workspaces) {
                if (!workspace.notificationsEnabled) continue
                for (chat in workspace.chats) {
                    if (!chat.hasUnnotifiedCompletion || chat.mutedNotifications) continue
                    if (postReplyReady(chat.tabId, chat.generationSequence)) {
                        workspaces.markCompletionNotified(chat.tabId, chat.generationSequence)
                    }
                }
            }
        }
    }

    private fun postReplyReady(tabId: TabId, generationSequence: Long): Boolean = runCatching {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCode(tabId),
            Intent(appContext, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_RESTORE_TAB_ID, tabId.value)
                .putExtra(BrowserActivity.EXTRA_AI_REPLY_GENERATION, generationSequence)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("ChatGPT reply ready")
            .setContentText("Tap to open the completed chat")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .build()
        notificationManager.notify(notificationId(tabId), notification)
        true
    }.getOrDefault(false)

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return notificationManager.areNotificationsEnabled()
    }

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ChatGPT replies",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Audible notifications when a ChatGPT reply finishes in Bubble."
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun requestCode(tabId: TabId): Int = tabId.value.hashCode() and 0x7fffffff
    private fun notificationId(tabId: TabId): Int = NOTIFICATION_ID_BASE xor requestCode(tabId)

    companion object {
        const val CHANNEL_ID = "chatgpt_replies"
        private const val GROUP_KEY = "com.mekromn.bubble.CHATGPT_REPLIES"
        private const val NOTIFICATION_ID_BASE = 0x43000000
    }
}
