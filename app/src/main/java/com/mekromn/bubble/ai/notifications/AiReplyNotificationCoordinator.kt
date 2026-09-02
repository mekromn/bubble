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
import com.mekromn.bubble.ai.model.AiChatState
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
    private val isChatVisible: (TabId) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()
    private val knownChatIds = linkedSetOf<TabId>()

    init {
        ensureChannel()
        scope.launch { workspaces.state.collect { state -> syncNotifications(state) } }
    }

    fun refresh() {
        scope.launch { syncNotifications(workspaces.state.value) }
    }

    private suspend fun syncNotifications(state: AiWorkspaceState) {
        if (!state.initialized) return
        mutex.withLock {
            val currentChats = state.workspaces.flatMap { it.chats }
            val currentIds = currentChats.mapTo(linkedSetOf()) { it.tabId }
            (knownChatIds - currentIds).forEach { notificationManager.cancel(notificationId(it)) }
            knownChatIds.clear()
            knownChatIds += currentIds

            val canPost = canPostNotifications()
            for (workspace in state.workspaces) {
                for (chat in workspace.chats) {
                    if (chat.state != AiChatState.COMPLETE_UNREAD) {
                        notificationManager.cancel(notificationId(chat.tabId))
                        continue
                    }

                    if (isChatVisible(chat.tabId)) {
                        notificationManager.cancel(notificationId(chat.tabId))
                        workspaces.markRead(chat.tabId)
                        continue
                    }

                    if (
                        canPost &&
                        workspace.notificationsEnabled &&
                        !chat.mutedNotifications &&
                        chat.hasUnnotifiedCompletion
                    ) {
                        if (postReplyReady(chat.tabId, chat.generationSequence)) {
                            workspaces.markCompletionNotified(chat.tabId, chat.generationSequence)
                        }
                    }
                }
            }
            updateGroupSummary(state, canPost)
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
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .build()
        notificationManager.notify(notificationId(tabId), notification)
        true
    }.getOrDefault(false)

    private fun updateGroupSummary(state: AiWorkspaceState, canPost: Boolean) {
        val unread = state.workspaces
            .flatMap { workspace -> workspace.chats.map { workspace to it } }
            .count { (workspace, chat) ->
                workspace.notificationsEnabled &&
                    !chat.mutedNotifications &&
                    chat.state == AiChatState.COMPLETE_UNREAD &&
                    !isChatVisible(chat.tabId)
            }
        if (!canPost || unread < 2) {
            notificationManager.cancel(GROUP_SUMMARY_ID)
            return
        }
        val summaryIntent = PendingIntent.getActivity(
            appContext,
            GROUP_SUMMARY_ID,
            Intent(appContext, BrowserActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("$unread ChatGPT replies ready")
            .setContentText("Open Bubble to choose a conversation")
            .setContentIntent(summaryIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(GROUP_SUMMARY_ID, summary)
    }

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
        private const val GROUP_SUMMARY_ID = 0x43ffffff
    }
}
