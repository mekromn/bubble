package com.mekromn.bubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

internal enum class VoiceNoticeKind(val channel: String, val label: String, val notificationId: Int) {
    INCOMING_CALL("google-voice-calls-v1", "Incoming calls", 5201),
    MESSAGE("google-voice-messages-v1", "Messages", 5202),
    MISSED_CALL("google-voice-missed-v1", "Missed calls", 5203),
    VOICEMAIL("google-voice-voicemail-v1", "Voicemail", 5204),
    OTHER("google-voice-other-v1", "Other Google Voice alerts", 5205)
}

/** Pure classification keeps channel selection deterministic and independently testable. */
internal object VoiceNoticeClassifier {
    fun classify(title: String?, text: String?, tag: String?): VoiceNoticeKind {
        val value = listOf(title, text, tag).joinToString(" ") { it.orEmpty() }.lowercase()
        return when {
            "missed call" in value || "missed-call" in value -> VoiceNoticeKind.MISSED_CALL
            "voicemail" in value || "voice mail" in value || "transcript" in value -> VoiceNoticeKind.VOICEMAIL
            "incoming call" in value || "call from" in value || "ringing" in value || "is calling" in value -> VoiceNoticeKind.INCOMING_CALL
            "message" in value || "text from" in value || "new text" in value || "sms" in value -> VoiceNoticeKind.MESSAGE
            else -> VoiceNoticeKind.OTHER
        }
    }
}

/**
 * Native Android presentation for Gecko Web Notifications. Google Voice gets isolated channels so
 * Android can independently control call, message, missed-call, voicemail and fallback alerts.
 * Notification text stays on-device and is never stored in Bubble workspace state or sent anywhere.
 */
internal object VoiceNotifications {
    const val STATUS_CHANNEL = "google-voice-status-v1"
    const val WEB_CHANNEL = "website-notifications-v1"
    private const val STATUS_ID = 5299
    private const val WEB_ID = 5298
    private const val CLICK = "com.mekromn.bubble.webnotification.CLICK"
    private const val DISMISS = "com.mekromn.bubble.webnotification.DISMISS"
    private const val TOKEN = "bubble.webnotification.token"

    private data class Active(val web: WebNotification, val androidTag: String, val androidId: Int,
        val tabId: String?, val targetUrl: String, val voice: Boolean)

    private val active = ConcurrentHashMap<String, Active>()
    private val tokenByObject = Collections.synchronizedMap(IdentityHashMap<WebNotification, String>())
    private val slotToToken = ConcurrentHashMap<String, String>()

    fun prepare(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, importance: Int, description: String, callSound: Boolean = false) {
            manager.createNotificationChannel(NotificationChannel(id, name, importance).apply {
                this.description = description
                enableVibration(importance >= NotificationManager.IMPORTANCE_DEFAULT)
                if (callSound) {
                    val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audio)
                    setBypassDnd(false)
                }
            })
        }
        channel(VoiceNoticeKind.INCOMING_CALL.channel, "Google Voice · incoming calls", NotificationManager.IMPORTANCE_HIGH,
            "Incoming Google Voice calls while a Voice tab is live in Bubble", true)
        channel(VoiceNoticeKind.MESSAGE.channel, "Google Voice · messages", NotificationManager.IMPORTANCE_HIGH,
            "New Google Voice text messages")
        channel(VoiceNoticeKind.MISSED_CALL.channel, "Google Voice · missed calls", NotificationManager.IMPORTANCE_HIGH,
            "Missed Google Voice calls")
        channel(VoiceNoticeKind.VOICEMAIL.channel, "Google Voice · voicemail", NotificationManager.IMPORTANCE_DEFAULT,
            "New Google Voice voicemail and transcript alerts")
        channel(VoiceNoticeKind.OTHER.channel, "Google Voice · other", NotificationManager.IMPORTANCE_DEFAULT,
            "Other notifications emitted by voice.google.com")
        channel(STATUS_CHANNEL, "Google Voice · connection status", NotificationManager.IMPORTANCE_HIGH,
            "Warn when a protected Google Voice tab cannot stay live")
        channel(WEB_CHANNEL, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT,
            "Notifications from other websites that Bubble is allowed to show")
    }

    fun install(context: Context, runtime: GeckoRuntime, workspace: Workspace) {
        prepare(context)
        runtime.setWebNotificationDelegate(object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) {
                show(context, workspace, notification)
            }
            override fun onCloseNotification(notification: WebNotification) {
                closeFromWeb(context, notification)
            }
        })
    }

    private fun show(context: Context, workspace: Workspace, web: WebNotification) {
        val voice = Policy.isVoice(web.source.orEmpty()) || Policy.isVoice(web.origin)
        val kind = if (voice) VoiceNoticeClassifier.classify(web.title, web.text, web.tag) else null
        val channel = kind?.channel ?: WEB_CHANNEL
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!notificationsUsable(context, channel)) {
            web.dismiss()
            if (voice) {
                workspace.notice = "Google Voice produced an alert, but its Android notification channel is disabled. Open Google Voice notification settings in Bubble."
                workspace.changed()
            }
            return
        }

        val tab = if (voice) workspace.tabs.firstOrNull { Policy.isVoice(it.url) } else
            workspace.tabs.firstOrNull { tab -> web.source?.let { Policy.host(it) == Policy.host(tab.url) } == true }
        if (voice && tab != null && tab.id != workspace.selectedId) {
            tab.unread = true
            workspace.changed(true)
        }

        val token = UUID.randomUUID().toString()
        val slot = if (web.tag.isNotBlank()) "${web.origin}|${web.tag}" else token
        slotToToken.put(slot, token)?.let { old -> retire(context, old, true) }
        val androidTag = "bubble-web:$token"
        val androidId = kind?.notificationId ?: WEB_ID
        val target = web.source?.takeIf(Policy::isWeb) ?: if (voice) Policy.VOICE_HOME else Policy.HOME
        val item = Active(web, androidTag, androidId, tab?.id, target, voice)
        active[token] = item
        tokenByObject[web] = token

        val click = PendingIntent.getBroadcast(context, token.hashCode(), Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = CLICK; data = Uri.parse("bubble://web-notification/$token/click"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, token.hashCode() xor 0x51a7, Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = DISMISS; data = Uri.parse("bubble://web-notification/$token/dismiss"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = web.title?.takeIf { it.isNotBlank() } ?: if (voice) "Google Voice" else Policy.host(target).ifBlank { "Website notification" }
        val text = web.text.orEmpty().take(4096)
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(512)).setContentIntent(click).setDeleteIntent(dismiss)
            .setAutoCancel(!web.requireInteraction).setOnlyAlertOnce(false).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(if (kind == VoiceNoticeKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
        if (text.isNotBlank()) builder.setContentText(text.take(512)).setStyle(Notification.BigTextStyle().bigText(text))
        if (web.requireInteraction) builder.setOngoing(kind == VoiceNoticeKind.INCOMING_CALL)
        if (!voice && web.silent) builder.setSilent(true)
        try {
            manager.notify(androidTag, androidId, builder.build())
            web.show()
        } catch (_: SecurityException) {
            active.remove(token); tokenByObject.remove(web); slotToToken.remove(slot, token); web.dismiss()
        }
    }

    private fun closeFromWeb(context: Context, web: WebNotification) {
        val token = tokenByObject.remove(web) ?: return
        retire(context, token, false)
        web.dismiss()
    }

    internal fun handle(context: Context, action: String?, token: String?) {
        if (token.isNullOrBlank()) return
        val item = active.remove(token) ?: return
        tokenByObject.remove(item.web)
        slotToToken.entries.removeAll { it.value == token }
        context.getSystemService(NotificationManager::class.java).cancel(item.androidTag, item.androidId)
        if (action == CLICK) {
            runCatching { item.web.click() }
            runCatching { item.web.dismiss() }
            openTarget(context, item)
        } else runCatching { item.web.dismiss() }
    }

    private fun retire(context: Context, token: String, dismissWeb: Boolean) {
        val item = active.remove(token) ?: return
        tokenByObject.remove(item.web)
        slotToToken.entries.removeAll { it.value == token }
        context.getSystemService(NotificationManager::class.java).cancel(item.androidTag, item.androidId)
        if (dismissWeb) runCatching { item.web.dismiss() }
    }

    private fun openTarget(context: Context, item: Active) {
        if (item.tabId != null) {
            try {
                NotificationReturnActivity.pending(context, item.tabId, FloatingMode.CHAT).send()
                return
            } catch (_: PendingIntent.CanceledException) { }
        }
        try {
            context.startActivity(Intent(context, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW; data = Uri.parse(item.targetUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        } catch (_: RuntimeException) { }
    }

    fun tabOffline(context: Context, tabId: String) {
        prepare(context)
        if (!notificationsUsable(context, STATUS_CHANNEL)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = NotificationReturnActivity.pending(context, tabId, FloatingMode.CHAT)
        manager.notify("voice-status:$tabId", STATUS_ID, Notification.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Google Voice needs attention")
            .setContentText("Its protected live tab stopped after repeated renderer failures. Tap to reconnect.")
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }

    fun clearStatus(context: Context, tabId: String) {
        context.getSystemService(NotificationManager::class.java).cancel("voice-status:$tabId", STATUS_ID)
    }

    fun channelSettings(context: Context, channel: String) {
        prepare(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appSettings(context: Context) {
        prepare(context)
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun readiness(context: Context): String {
        prepare(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "Android permission off"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "app notifications off"
        val blocked = VoiceNoticeKind.entries.count { manager.getNotificationChannel(it.channel)?.importance == NotificationManager.IMPORTANCE_NONE }
        return if (blocked == 0) "all Voice channels enabled" else "$blocked Voice channel${if (blocked == 1) "" else "s"} disabled"
    }

    private fun notificationsUsable(context: Context, channel: String): Boolean {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
}

class VoiceNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceNotifications.handle(context.applicationContext, intent.action, intent.getStringExtra("bubble.webnotification.token"))
    }
}
