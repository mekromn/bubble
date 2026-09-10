package com.mekromn.bubble

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
    INCOMING_CALL("google-voice-calls-v2", "Incoming calls", 5201),
    MESSAGE("google-voice-messages-v2", "Messages", 5202),
    MISSED_CALL("google-voice-missed-v2", "Missed calls", 5203),
    VOICEMAIL("google-voice-voicemail-v2", "Voicemail", 5204),
    OTHER("google-voice-other-v2", "Other Google Voice alerts", 5205)
}

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

internal data class VoiceContactInfo(val displayName: String?, val phone: String?)

/** Pure formatter for contact-like data already present in Google Voice's Web Notification payload. */
internal object VoiceContactPolicy {
    private val phoneCandidate = Regex("(?<!\\d)(\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    private val senderPrefix = Regex(
        "^(?:google\\s+voice\\s*[-:–—]\\s*)?(?:new\\s+)?" +
            "(?:message|text|sms|incoming\\s+call|call|missed\\s+call|voicemail|voice\\s+mail)" +
            "(?:\\s+(?:from|by))?\\s*[:\\-–—]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val explicitSender = Regex(
        "^(?:text|message|sms|incoming\\s+call|call|missed\\s+call|voicemail|voice\\s+mail)" +
            "\\s+(?:from|by)\\s+([^:\\n]{1,120})",
        RegexOption.IGNORE_CASE
    )
    private val generic = setOf(
        "google voice", "new message", "message", "new text", "text", "sms", "incoming call",
        "call", "missed call", "new voicemail", "voicemail", "voice mail", "notification", "alert"
    )

    fun extract(title: String?, text: String?, tag: String?): VoiceContactInfo {
        val sources = listOf(title, text, tag).map { it.orEmpty().trim() }
        val phone = sources.asSequence().mapNotNull(::phoneFrom).firstOrNull()
        val fromTitle = cleanName(title.orEmpty(), phone)
        val fromText = explicitSender.find(text.orEmpty().trim())?.groupValues?.getOrNull(1)
            ?.let { cleanName(it, phone) }
        return VoiceContactInfo(fromTitle ?: fromText, phone)
    }

    private fun phoneFrom(value: String): String? {
        for (match in phoneCandidate.findAll(value)) {
            val raw = match.groupValues[1].trim().trimEnd('.', ',', ';', ':')
            val digits = raw.filter(Char::isDigit)
            if (digits.length in 7..15) return raw
        }
        return null
    }

    private fun cleanName(raw: String, phone: String?): String? {
        var value = raw.replace('\u00a0', ' ').trim()
        if (value.isBlank()) return null
        value = senderPrefix.replace(value, "").trim()
        if (phone != null) value = value.replace(phone, " ").trim()
        value = value.replace(Regex("\\s+(?:via|on)\\s+google\\s+voice$", RegexOption.IGNORE_CASE), "").trim()
        value = value.trim(' ', '-', '–', '—', ':', '(', ')', '[', ']')
        if (value.isBlank() || value.lowercase() in generic) return null
        if (value.length > 120 || value.any { it == '\n' || it == '\r' }) return null
        if (value.filter(Char::isDigit).length >= 7 && value.filter { it.isLetter() }.isEmpty()) return null
        return value
    }

    fun fallbackTitle(kind: VoiceNoticeKind): String = when (kind) {
        VoiceNoticeKind.INCOMING_CALL -> "Incoming Google Voice call"
        VoiceNoticeKind.MESSAGE -> "New Google Voice message"
        VoiceNoticeKind.MISSED_CALL -> "Google Voice missed call"
        VoiceNoticeKind.VOICEMAIL -> "New Google Voice voicemail"
        VoiceNoticeKind.OTHER -> "Google Voice"
    }

    fun title(kind: VoiceNoticeKind, webTitle: String?, info: VoiceContactInfo): String =
        info.displayName ?: info.phone ?: webTitle?.takeIf { it.isNotBlank() && it.trim().lowercase() !in generic }
        ?: fallbackTitle(kind)

    fun subText(kind: VoiceNoticeKind, info: VoiceContactInfo): String = buildString {
        append("Google Voice")
        info.phone?.let { append(" · ").append(it) }
        if (info.phone == null) append(" · ").append(kind.label)
    }

    fun telUri(phone: String): String? {
        val trimmed = phone.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return "tel:" + (if (trimmed.startsWith('+')) "+" else "") + digits
    }
}

/** First-class exact-origin Google Voice Web Notifications with separate Android channels. */
internal object VoiceNotifications {
    const val STATUS_CHANNEL = "google-voice-status-v2"
    private const val STATUS_ID = 5299
    private const val CHAT_WEB_ID = 5301
    private const val CLICK = "com.mekromn.bubble.voicenotification.CLICK"
    private const val DISMISS = "com.mekromn.bubble.voicenotification.DISMISS"
    private const val COPY_NUMBER = "com.mekromn.bubble.voicenotification.COPY_NUMBER"
    private const val TOKEN = "bubble.webnotification.token"
    private const val PHONE = "bubble.webnotification.phone"
    private val main = Handler(Looper.getMainLooper())

    private data class Active(val web: WebNotification, val androidTag: String, val androidId: Int,
        val tabId: String?, val targetUrl: String)

    private val active = ConcurrentHashMap<String, Active>()
    private val tokenByObject = Collections.synchronizedMap(IdentityHashMap<WebNotification, String>())
    private val slotToToken = ConcurrentHashMap<String, String>()
    private val permissionCallbacks = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<GeckoSession.PermissionDelegate.Callback, Boolean>()))
    private var permissionActivityPending = false
    private var voiceWebEvents = 0
    private var lastVoiceKind: VoiceNoticeKind? = null

    fun prepare(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, description: String, callSound: Boolean = false) {
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                this.description = description
                enableVibration(true); setShowBadge(true)
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

    fun install(context: Context, runtime: GeckoRuntime, workspace: Workspace) {
        prepare(context); Replies.prepare(context)
        runtime.setWebNotificationDelegate(object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) { main.post { show(context, workspace, notification) } }
            override fun onCloseNotification(notification: WebNotification) { main.post { closeFromWeb(context, notification) } }
        })
    }

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

            override fun onAndroidPermissionsRequest(s: GeckoSession, permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback) {
                if (tab.session !== s) { callback.reject(); return }
                val requested = permissions.orEmpty()
                val notificationOnly = requested.isNotEmpty() && requested.all { it == Manifest.permission.POST_NOTIFICATIONS }
                if (!notificationOnly) { callback.reject(); return }
                if (Build.VERSION.SDK_INT < 33 || androidPermissionGranted(context)) { callback.grant(); return }
                permissionCallbacks += callback
                requestAndroidPermission(context)
            }
        }
    }

    private fun requestAndroidPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 33 || androidPermissionGranted(context)) { resolveAndroidPermission(true); return }
        if (permissionActivityPending) return
        permissionActivityPending = true
        try { context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { permissionActivityPending = false; resolveAndroidPermission(false) }
    }

    internal fun resolveAndroidPermission(granted: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { resolveAndroidPermission(granted) }; return }
        permissionActivityPending = false
        val callbacks = synchronized(permissionCallbacks) { permissionCallbacks.toList().also { permissionCallbacks.clear() } }
        callbacks.forEach { if (granted) it.grant() else it.reject() }
    }

    private fun show(context: Context, workspace: Workspace, web: WebNotification) {
        val isVoice = Policy.isVoice(web.source.orEmpty()) || Policy.isVoice(web.origin)
        val isChat = Policy.isChat(web.source.orEmpty()) || Policy.isChat(web.origin)
        if (!isVoice && !isChat) { runCatching { web.dismiss() }; return }
        if (isChat) { showChatWebNotification(context, workspace, web); return }

        voiceWebEvents++
        val voiceTabs = workspace.tabs.filter { Policy.isVoice(it.url) }
        if (voiceTabs.isEmpty()) { runCatching { web.dismiss() }; return }
        val kind = VoiceNoticeClassifier.classify(web.title, web.text, web.tag)
        lastVoiceKind = kind
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!notificationsUsable(context, kind.channel)) {
            runCatching { web.dismiss() }
            workspace.notice = "Google Voice produced a web alert, but Android notification delivery is disabled. Open Bubble notification settings."
            workspace.changed()
            return
        }
        val tab = voiceTabs.singleOrNull()
        if (tab != null && tab.id != workspace.selectedId) { tab.unread = true; workspace.changed(true) }
        val contact = VoiceContactPolicy.extract(web.title, web.text, web.tag)
        postWeb(context, web, kind.channel, kind.notificationId, tab?.id,
            web.source?.takeIf(Policy::isVoice) ?: Policy.VOICE_HOME,
            VoiceContactPolicy.title(kind, web.title, contact),
            web.text.orEmpty().take(4096),
            if (kind == VoiceNoticeKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE,
            web.requireInteraction && kind == VoiceNoticeKind.INCOMING_CALL,
            contact, kind)
    }

    private fun showChatWebNotification(context: Context, workspace: Workspace, web: WebNotification) {
        if (!Replies.enabled(context)) { runCatching { web.dismiss() }; return }
        val tabs = workspace.tabs.filter { Policy.isChat(it.url) }
        if (tabs.isEmpty()) { runCatching { web.dismiss() }; return }
        val tab = tabs.singleOrNull()
        if (tab != null && tab.id != workspace.selectedId) { tab.unread = true; workspace.changed(true) }
        postWeb(context, web, Replies.CHANNEL, CHAT_WEB_ID, tab?.id,
            web.source?.takeIf(Policy::isChat) ?: Policy.HOME,
            "ChatGPT notification", "Tap to open ChatGPT", Notification.CATEGORY_MESSAGE, false)
    }

    private fun postWeb(context: Context, web: WebNotification, channel: String, id: Int, tabId: String?,
        targetUrl: String, title: String, text: String, category: String, ongoing: Boolean,
        contact: VoiceContactInfo? = null, voiceKind: VoiceNoticeKind? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val token = UUID.randomUUID().toString()
        val slot = if (web.tag.isNotBlank()) "${web.origin}|${web.tag}" else token
        slotToToken.put(slot, token)?.let { old -> retire(context, old, true) }
        val androidTag = "bubble-web:$token"
        val item = Active(web, androidTag, id, tabId, targetUrl)
        active[token] = item; tokenByObject[web] = token
        val click = PendingIntent.getBroadcast(context, token.hashCode(), Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = CLICK; data = Uri.parse("bubble://web-notification/$token/click"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, token.hashCode() xor 0x51a7, Intent(context, VoiceNotificationReceiver::class.java).apply {
            action = DISMISS; data = Uri.parse("bubble://web-notification/$token/dismiss"); putExtra(TOKEN, token)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(512)).setContentIntent(click).setDeleteIntent(dismiss)
            .setAutoCancel(!ongoing).setOnlyAlertOnce(false).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(category)
        if (text.isNotBlank()) builder.setContentText(text.take(512)).setStyle(Notification.BigTextStyle().bigText(text))
        if (contact != null && voiceKind != null) {
            builder.setSubText(VoiceContactPolicy.subText(voiceKind, contact).take(256))
            contact.phone?.let { phone ->
                VoiceContactPolicy.telUri(phone)?.let(builder::addPerson)
                val copy = PendingIntent.getBroadcast(context, token.hashCode() xor 0x2c09,
                    Intent(context, VoiceNotificationReceiver::class.java).apply {
                        action = COPY_NUMBER
                        data = Uri.parse("bubble://web-notification/$token/copy-number")
                        putExtra(TOKEN, token); putExtra(PHONE, phone)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, "Copy number", copy)
            }
        }
        if (ongoing) builder.setOngoing(true)
        try { manager.notify(androidTag, id, builder.build()); web.show() }
        catch (_: SecurityException) {
            active.remove(token); tokenByObject.remove(web); slotToToken.remove(slot, token); runCatching { web.dismiss() }
        }
    }

    private fun closeFromWeb(context: Context, web: WebNotification) {
        val token = tokenByObject.remove(web) ?: return
        retire(context, token, false); runCatching { web.dismiss() }
    }

    internal fun handle(context: Context, action: String?, token: String?, phone: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { handle(context, action, token, phone) }; return }
        if (action == COPY_NUMBER) {
            val value = phone?.takeIf { VoiceContactPolicy.telUri(it) != null } ?: return
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Google Voice phone number", value))
            Toast.makeText(context, "Phone number copied", Toast.LENGTH_SHORT).show()
            return
        }
        if (token.isNullOrBlank()) return
        val item = active.remove(token) ?: return
        tokenByObject.remove(item.web); slotToToken.entries.removeAll { it.value == token }
        context.getSystemService(NotificationManager::class.java).cancel(item.androidTag, item.androidId)
        if (action == CLICK) {
            runCatching { item.web.click() }; runCatching { item.web.dismiss() }; openTarget(context, item)
        } else runCatching { item.web.dismiss() }
    }

    private fun retire(context: Context, token: String, dismissWeb: Boolean) {
        val item = active.remove(token) ?: return
        tokenByObject.remove(item.web); slotToToken.entries.removeAll { it.value == token }
        context.getSystemService(NotificationManager::class.java).cancel(item.androidTag, item.androidId)
        if (dismissWeb) runCatching { item.web.dismiss() }
    }

    private fun openTarget(context: Context, item: Active) {
        if (item.tabId != null) {
            try { NotificationReturnActivity.pending(context, item.tabId, FloatingMode.CHAT).send(); return }
            catch (_: PendingIntent.CanceledException) { }
        }
        val workspace = Workspace.peek()
        if (workspace != null && workspace.tabs.count { Policy.host(it.url) == Policy.host(item.targetUrl) } > 1) {
            try { NotificationReturnActivity.pending(context, null, FloatingMode.CHOOSER).send(); return }
            catch (_: PendingIntent.CanceledException) { }
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

    fun test(context: Context, kind: VoiceNoticeKind = VoiceNoticeKind.MESSAGE): Boolean {
        prepare(context)
        if (!notificationsUsable(context, kind.channel)) return false
        return try {
            val target = Workspace.peek()?.tabs?.firstOrNull { Policy.isVoice(it.url) }?.id
            context.getSystemService(NotificationManager::class.java).notify("voice-test:${kind.name}", kind.notificationId + 100,
                Notification.Builder(context, kind.channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (kind == VoiceNoticeKind.INCOMING_CALL) "Google Voice call alert test" else "Google Voice alert test")
                    .setContentText("Bubble's ${kind.label.lowercase()} notification channel is working.")
                    .setContentIntent(NotificationReturnActivity.pending(context, target, FloatingMode.CHAT))
                    .setAutoCancel(true).setCategory(if (kind == VoiceNoticeKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }

    fun controls(anchor: View, workspace: Workspace, tabId: String) {
        val tab = workspace.tabs.firstOrNull { it.id == tabId && Policy.isVoice(it.url) } ?: return
        prepare(anchor.context)
        val panel = QuickPanel.open(anchor, workspace, "Google Voice alerts", 560) ?: return
        fun d(n: Int) = Ui.dp(anchor.context, n.toFloat())
        val scroll = ScrollView(anchor.context)
        val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, d(10), d(8)) }
        scroll.addView(body); panel.body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.text(anchor.context,
            "${readiness(anchor.context)} · protected live tab\nWeb notification events received this session: $voiceWebEvents${lastVoiceKind?.let { " · last: ${it.label}" }.orEmpty()}\n\nVoice tabs stay resident/high-priority while Bubble is running. Each alert type has its own Android channel. Bubble does not persist Voice message contents.",
            12f, Ui.MUTED).apply { setPadding(d(6), d(8), d(6), d(10)) })
        fun row(label: String, action: () -> Unit) {
            body.addView(Ui.text(anchor.context, label, 14f, Ui.ACCENT, true).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), 0, d(12), 0); background = Ui.ripple(anchor.context)
                isClickable = true; isFocusable = true; setOnClickListener { panel.finish(action) }
            }, LinearLayout.LayoutParams(-1, d(50)))
        }
        if (!androidPermissionGranted(anchor.context)) row("Enable Android notification permission") { ensurePermission(anchor.context) }
        row("Send test message alert") { if (!test(anchor.context, VoiceNoticeKind.MESSAGE)) ensurePermission(anchor.context) }
        row("Send test incoming-call alert") { if (!test(anchor.context, VoiceNoticeKind.INCOMING_CALL)) ensurePermission(anchor.context) }
        VoiceNoticeKind.entries.forEach { kind -> row("${kind.label} settings") { channelSettings(anchor.context, kind.channel) } }
        row("Connection warning settings") { channelSettings(anchor.context, STATUS_CHANNEL) }
        row("All Bubble notification settings") { appSettings(anchor.context) }
        row("Open another Google Voice tab · same profile") {
            workspace.create(Policy.VOICE_HOME, tab.profileId); BubbleService.active?.window?.openChat(workspace.selectedId)
        }
    }

    fun channelSettings(context: Context, channel: String) {
        prepare(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appSettings(context: Context) {
        prepare(context); Replies.prepare(context)
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun ensurePermission(context: Context) {
        prepare(context); Replies.prepare(context)
        if (androidPermissionGranted(context)) { appSettings(context); return }
        requestAndroidPermission(context)
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

    internal fun androidPermissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notificationsUsable(context: Context, channel: String): Boolean {
        prepare(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return androidPermissionGranted(context) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
}

class VoiceNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceNotifications.handle(context.applicationContext, intent.action,
            intent.getStringExtra("bubble.webnotification.token"),
            intent.getStringExtra("bubble.webnotification.phone"))
    }
}

class VoicePermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Replies.prepare(this); VoiceNotifications.prepare(this)
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            complete(true); return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, result: IntArray) {
        super.onRequestPermissionsResult(code, permissions, result)
        if (code == REQUEST) complete(result.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun complete(granted: Boolean) {
        VoiceNotifications.resolveAndroidPermission(granted)
        if (granted) {
            Workspace.peek()?.tabs?.filter { Policy.isVoice(it.url) }?.forEach { tab -> tab.session?.takeIf { it.isOpen }?.reload() }
            Toast.makeText(this, "Bubble notification permission enabled. ChatGPT and Google Voice alert channels are ready.", Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, "Bubble notifications are still disabled. Alerts cannot appear until Android permission is enabled.", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object { private const val REQUEST = 902 }
}
