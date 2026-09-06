package com.mekromn.bubble

import android.Manifest
import android.app.Activity
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
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
 * First-class Google Voice Web Notifications. The exact voice.google.com origin gets isolated
 * Android channels for calls/messages/missed calls/voicemail/other alerts. Notification contents
 * are transient Android UI only: Bubble never writes them to workspace state, notes, logs or a
 * network service.
 */
internal object VoiceNotifications {
    const val STATUS_CHANNEL = "google-voice-status-v1"
    private const val STATUS_ID = 5299
    private const val CLICK = "com.mekromn.bubble.voicenotification.CLICK"
    private const val DISMISS = "com.mekromn.bubble.voicenotification.DISMISS"
    private const val TOKEN = "bubble.webnotification.token"
    private val main = Handler(Looper.getMainLooper())

    private data class Active(val web: WebNotification, val androidTag: String, val androidId: Int,
        val tabId: String?, val targetUrl: String)

    private val active = ConcurrentHashMap<String, Active>()
    private val tokenByObject = Collections.synchronizedMap(IdentityHashMap<WebNotification, String>())
    private val slotToToken = ConcurrentHashMap<String, String>()

    fun prepare(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, description: String, callSound: Boolean = false) {
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                this.description = description
                enableVibration(true)
                if (callSound) {
                    val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audio)
                    setBypassDnd(false)
                }
            })
        }
        channel(VoiceNoticeKind.INCOMING_CALL.channel, "Google Voice · incoming calls",
            "Incoming Google Voice calls while a protected Voice tab is live", true)
        channel(VoiceNoticeKind.MESSAGE.channel, "Google Voice · messages", "New Google Voice text messages")
        channel(VoiceNoticeKind.MISSED_CALL.channel, "Google Voice · missed calls", "Missed Google Voice calls")
        channel(VoiceNoticeKind.VOICEMAIL.channel, "Google Voice · voicemail", "New Google Voice voicemail and transcript alerts")
        channel(VoiceNoticeKind.OTHER.channel, "Google Voice · other", "Other notifications emitted by voice.google.com")
        channel(STATUS_CHANNEL, "Google Voice · connection status", "Warn when a protected Google Voice renderer cannot stay live")
    }

    /** Runtime callback may arrive off-main; all Gecko notification callbacks are returned on main. */
    fun install(context: Context, runtime: GeckoRuntime, workspace: Workspace) {
        prepare(context)
        runtime.setWebNotificationDelegate(object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) {
                main.post { show(context, workspace, notification) }
            }
            override fun onCloseNotification(notification: WebNotification) {
                main.post { closeFromWeb(context, notification) }
            }
        })
    }

    /** Only the exact Google Voice origin receives automatic site-notification permission. */
    fun installSessionPermissions(context: Context, tab: ChatTab, session: GeckoSession) {
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(s: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? {
                if (tab.session !== s || permission.permission != GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) return null
                if (!Policy.isVoice(tab.url) || !Policy.isVoice(permission.uri)) return null
                if (permission.contextId != null && permission.contextId != tab.profileId) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }

            override fun onAndroidPermissionsRequest(s: GeckoSession, permissions: Array<String>,
                callback: GeckoSession.PermissionDelegate.Callback) {
                if (tab.session !== s) { callback.reject(); return }
                // Never proxy unrelated Android permissions. The only app permission Voice web
                // notifications can use here is POST_NOTIFICATIONS, and it must already be granted
                // through Bubble's explicit Android permission UI.
                val notificationOnly = permissions.isNotEmpty() && permissions.all { it == Manifest.permission.POST_NOTIFICATIONS }
                val granted = notificationOnly && (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                if (granted) callback.grant() else callback.reject()
            }
        }
    }

    private fun show(context: Context, workspace: Workspace, web: WebNotification) {
        val voice = Policy.isVoice(web.source.orEmpty()) || Policy.isVoice(web.origin)
        if (!voice) {
            // This delegate exists for Google Voice. Do not silently turn Bubble into a blanket
            // notification broker for unrelated sites.
            runCatching { web.dismiss() }
            return
        }
        val kind = VoiceNoticeClassifier.classify(web.title, web.text, web.tag)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!notificationsUsable(context, kind.channel)) {
            runCatching { web.dismiss() }
            workspace.notice = "Google Voice produced an alert, but its Android notification channel is disabled. Open Google Voice notification controls in Bubble."
            workspace.changed()
            return
        }

        // WebNotification does not expose the originating GeckoSession. Route directly only when
        // there is one unambiguous Voice tab; with several profiles/accounts, open the chooser
        // instead of risking the wrong account.
        val voiceTabs = workspace.tabs.filter { Policy.isVoice(it.url) }
        val tab = voiceTabs.singleOrNull()
        if (tab != null && tab.id != workspace.selectedId) {
            tab.unread = true
            workspace.changed(true)
        }

        val token = UUID.randomUUID().toString()
        val slot = if (web.tag.isNotBlank()) "${web.origin}|${web.tag}" else token
        slotToToken.put(slot, token)?.let { old -> retire(context, old, true) }
        val androidTag = "bubble-voice:$token"
        val target = web.source?.takeIf(Policy::isVoice) ?: Policy.VOICE_HOME
        val item = Active(web, androidTag, kind.notificationId, tab?.id, target)
        active[token] = item
        tokenByObject[web] = token

        val click = PendingIntent.getBroadcast(context, token.hashCode(), Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = CLICK; data = Uri.parse("bubble://voice-notification/$token/click"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, token.hashCode() xor 0x51a7, Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = DISMISS; data = Uri.parse("bubble://voice-notification/$token/dismiss"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = web.title?.takeIf { it.isNotBlank() } ?: "Google Voice"
        val text = web.text.orEmpty().take(4096)
        val builder = Notification.Builder(context, kind.channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(512)).setContentIntent(click).setDeleteIntent(dismiss)
            .setAutoCancel(!web.requireInteraction).setOnlyAlertOnce(false).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(if (kind == VoiceNoticeKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
        if (text.isNotBlank()) builder.setContentText(text.take(512)).setStyle(Notification.BigTextStyle().bigText(text))
        if (web.requireInteraction && kind == VoiceNoticeKind.INCOMING_CALL) builder.setOngoing(true)
        try {
            manager.notify(androidTag, kind.notificationId, builder.build())
            web.show()
        } catch (_: SecurityException) {
            active.remove(token); tokenByObject.remove(web); slotToToken.remove(slot, token); runCatching { web.dismiss() }
        }
    }

    private fun closeFromWeb(context: Context, web: WebNotification) {
        val token = tokenByObject.remove(web) ?: return
        retire(context, token, false)
        runCatching { web.dismiss() }
    }

    internal fun handle(context: Context, action: String?, token: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { handle(context, action, token) }; return }
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
        val workspace = Workspace.peek()
        if (workspace != null && workspace.tabs.count { Policy.isVoice(it.url) } > 1) {
            try {
                NotificationReturnActivity.pending(context, null, FloatingMode.CHOOSER).send()
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

    fun controls(anchor: View, workspace: Workspace, tabId: String) {
        val tab = workspace.tabs.firstOrNull { it.id == tabId && Policy.isVoice(it.url) } ?: return
        prepare(anchor.context)
        val panel = QuickPanel.open(anchor, workspace, "Google Voice alerts", 520) ?: return
        fun d(n: Int) = Ui.dp(anchor.context, n.toFloat())
        val scroll = ScrollView(anchor.context)
        val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(10), d(8)) }
        scroll.addView(body); panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.text(anchor.context,
            "${readiness(anchor.context)} · protected live tab\n\nVoice tabs stay resident/high-priority while Bubble is running. Each alert type has its own Android channel, so calls, messages, missed calls and voicemail can have independent sound/vibration/visibility settings. Bubble does not save Voice message contents.",
            12f, Ui.MUTED).apply { setPadding(d(6), d(8), d(6), d(10)) })
        fun row(label: String, action: () -> Unit) {
            body.addView(Ui.text(anchor.context, label, 14f, Ui.ACCENT, true).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), 0, d(12), 0); background = Ui.ripple(anchor.context)
                isClickable = true; isFocusable = true; setOnClickListener { panel.finish(action) }
            }, LinearLayout.LayoutParams(-1, d(50)))
        }
        if (!androidPermissionGranted(anchor.context)) row("Enable Android notification permission") { ensurePermission(anchor.context) }
        VoiceNoticeKind.entries.forEach { kind -> row("${kind.label} settings") { channelSettings(anchor.context, kind.channel) } }
        row("Connection warning settings") { channelSettings(anchor.context, STATUS_CHANNEL) }
        row("All Bubble notification settings") { appSettings(anchor.context) }
        row("Open another Google Voice tab · same profile") {
            workspace.create(Policy.VOICE_HOME, tab.profileId)
            BubbleService.active?.window?.openChat(workspace.selectedId)
        }
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

    fun ensurePermission(context: Context) {
        prepare(context)
        if (androidPermissionGranted(context)) { appSettings(context); return }
        try { context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { appSettings(context) }
    }

    fun readiness(context: Context): String {
        prepare(context)
        if (!androidPermissionGranted(context)) return "Android notification permission off"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "Bubble notifications off"
        val blocked = (VoiceNoticeKind.entries.map { it.channel } + STATUS_CHANNEL)
            .count { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
        return if (blocked == 0) "all Voice channels enabled" else "$blocked Voice channel${if (blocked == 1) "" else "s"} disabled"
    }

    private fun androidPermissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationsUsable(context: Context, channel: String): Boolean {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return androidPermissionGranted(context) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
}

class VoiceNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceNotifications.handle(context.applicationContext, intent.action, intent.getStringExtra("bubble.webnotification.token"))
    }
}

/** Small transient host so permission can be requested from either fullscreen or overlay UI. */
class VoicePermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, result: IntArray) {
        super.onRequestPermissionsResult(code, permissions, result)
        if (code == REQUEST && result.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Google Voice notification channels are ready. You can customize each one separately.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object { private const val REQUEST = 902 }
}
