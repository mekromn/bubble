package com.mekromn.bubble

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Shared Android notification gate for both ChatGPT and Google Voice.
 * This deliberately tests OS permission/channel delivery separately from website event detection. */
internal object NotificationHealth {
    private const val PREFS = "notification-health"
    private const val PERMISSION_PROMPT = "permission-prompt-v3"
    private const val APP_BLOCKED_PROMPT = "app-blocked-prompt-v3"
    private const val CHANNEL_BLOCKED_PROMPT = "channel-blocked-prompt-v3"
    private const val TEST_OFFER = "test-offer-v3"

    fun prepare(context: Context) {
        Replies.prepare(context); VoiceNotifications.prepare(context)
    }

    fun maybePrompt(activity: Activity) {
        prepare(activity)
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = activity.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (prefs.getBoolean(PERMISSION_PROMPT, false)) return
            prefs.edit().putBoolean(PERMISSION_PROMPT, true).apply()
            AlertDialog.Builder(activity).setTitle("Enable Bubble notifications?")
                .setMessage("Android notification permission is required for both ChatGPT reply alerts and Google Voice calls, messages, missed calls and voicemail. Without it, neither alert system can appear.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Enable") { _, _ -> VoiceNotifications.ensurePermission(activity) }
                .show()
            return
        }
        if (!manager.areNotificationsEnabled()) {
            if (prefs.getBoolean(APP_BLOCKED_PROMPT, false)) return
            prefs.edit().putBoolean(APP_BLOCKED_PROMPT, true).apply()
            AlertDialog.Builder(activity).setTitle("Bubble notifications are disabled")
                .setMessage("Android is blocking all Bubble notifications, including ChatGPT and Google Voice. Open system settings to enable them.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open settings") { _, _ -> VoiceNotifications.appSettings(activity) }
                .show()
            return
        }
        val chatBlocked = manager.getNotificationChannel(Replies.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        val voiceBlocked = VoiceNoticeKind.entries.all { manager.getNotificationChannel(it.channel)?.importance == NotificationManager.IMPORTANCE_NONE }
        if ((chatBlocked || voiceBlocked) && !prefs.getBoolean(CHANNEL_BLOCKED_PROMPT, false)) {
            prefs.edit().putBoolean(CHANNEL_BLOCKED_PROMPT, true).apply()
            AlertDialog.Builder(activity).setTitle("An alert channel is disabled")
                .setMessage("${if (chatBlocked) "ChatGPT reply alerts are off. " else ""}${if (voiceBlocked) "All Google Voice alert channels are off." else ""} Open Bubble's Android notification settings to restore them.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open settings") { _, _ -> VoiceNotifications.appSettings(activity) }
                .show()
            return
        }
        if (!prefs.getBoolean(TEST_OFFER, false)) {
            prefs.edit().putBoolean(TEST_OFFER, true).apply()
            AlertDialog.Builder(activity).setTitle("Test Bubble alerts now?")
                .setMessage("Send one local ChatGPT test alert and one Google Voice test alert. This verifies Android permission and channel delivery without waiting for either website.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Send tests") { _, _ ->
                    val chat = Replies.test(activity)
                    val voice = VoiceNotifications.test(activity, VoiceNoticeKind.MESSAGE)
                    if (!chat || !voice) Toast.makeText(activity, "One or more Android alert channels are still blocked.", Toast.LENGTH_LONG).show()
                }.show()
        }
    }

    fun status(context: Context): String {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "Android permission off"
        if (!manager.areNotificationsEnabled()) return "all Bubble notifications off"
        val chat = if (Replies.enabled(context)) "ChatGPT ready" else "ChatGPT channel off"
        val voice = VoiceNotifications.readiness(context)
        return "$chat · $voice"
    }

    fun openAppSettings(context: Context) {
        prepare(context)
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
