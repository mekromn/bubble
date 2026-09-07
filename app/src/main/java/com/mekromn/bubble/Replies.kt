package com.mekromn.bubble

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** One completion alert per validated generation. No conversation text or titles leave Gecko. */
internal object Replies {
    // New channel generation intentionally resets the broken/silent development-channel state.
    const val CHANNEL = "chatgpt-replies-v3"
    private const val GROUP = "bubble.chatgpt.replies"
    private const val SUMMARY = 4002
    private const val TEST_ID = 4099
    fun open(context: Context, id: String?, tray: Boolean = false): PendingIntent {
        if (!tray) return NotificationReturnActivity.pending(context, id, if (id == null) FloatingMode.BUBBLE else FloatingMode.CHAT)
        return PendingIntent.getActivity(context, 0, Intent(context, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("bubble://workspace/${id ?: "selected"}/tabs")
            putExtra(BrowserActivity.EXTRA_TAB, id); putExtra(BrowserActivity.EXTRA_TRAY, true)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun prepare(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ChatGPT reply alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a background ChatGPT reply completes; conversation text is not included"
                enableVibration(true); setShowBadge(true)
            })
    }
    fun enabled(context: Context): Boolean {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun readiness(context: Context): String {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "Android notification permission off"
        if (!manager.areNotificationsEnabled()) return "Bubble notifications off"
        return if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) "ChatGPT reply channel off" else "ChatGPT reply alerts ready"
    }
    fun settings(context: Context) {
        prepare(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun finished(context: Context, id: String) {
        if (!enabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val note = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your ChatGPT reply is ready").setContentText("Tap to open this conversation")
            .setContentIntent(open(context, id)).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setGroup(GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).build()
        try { manager.notify(id, 2, note); updateGroup(context) } catch (_: SecurityException) { }
    }
    /** Local diagnostic: proves Android permission/channel delivery independently of DOM reply detection. */
    fun test(context: Context): Boolean {
        if (!enabled(context)) return false
        return try {
            context.getSystemService(NotificationManager::class.java).notify("chatgpt-test", TEST_ID,
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("ChatGPT alert test").setContentText("Bubble's Android reply-alert channel is working.")
                    .setContentIntent(open(context, null, true)).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }
    fun clear(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, 2)
        updateGroup(context)
    }
    private fun updateGroup(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val count = manager.activeNotifications.count { it.id == 2 && it.notification.group == GROUP }
        if (count < 2) { manager.cancel(SUMMARY); return }
        manager.notify(SUMMARY, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$count ChatGPT replies are ready").setContentText("Open your floating workspace")
            .setContentIntent(NotificationReturnActivity.pending(context, null, FloatingMode.CHOOSER))
            .setGroup(GROUP).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }
}
