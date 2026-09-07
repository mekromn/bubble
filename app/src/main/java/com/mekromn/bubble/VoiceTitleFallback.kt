package com.mekromn.bubble

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock

internal data class VoiceTitleSignal(val kind: VoiceNoticeKind, val unreadCount: Int, val ringing: Boolean)

/**
 * Privacy-preserving Google Voice fallback derived only from the tab title + exact Voice URL.
 * Google Voice can update its unread title without emitting Web Notification events through Gecko.
 * We never read or persist message bodies, sender names, transcripts, cookies, or account IDs here.
 */
internal object VoiceTitlePolicy {
    private val unread = Regex("\\((\\d{1,4})\\)")

    fun parse(title: String, url: String): VoiceTitleSignal? {
        if (!Policy.isVoice(url)) return null
        val value = "$title $url".lowercase()
        val ringing = listOf("incoming call", "is calling", "call from", "ringing").any { it in value }
        val kind = when {
            ringing -> VoiceNoticeKind.INCOMING_CALL
            "missed call" in value || "missed-call" in value -> VoiceNoticeKind.MISSED_CALL
            "voicemail" in value || "voice mail" in value || "/voicemail" in value -> VoiceNoticeKind.VOICEMAIL
            "message" in value || "messages" in value || "/messages" in value || "sms" in value -> VoiceNoticeKind.MESSAGE
            "/calls" in value || " calls" in value -> VoiceNoticeKind.MISSED_CALL
            else -> VoiceNoticeKind.OTHER
        }
        val count = unread.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 9999) ?: 0
        return VoiceTitleSignal(kind, count, ringing)
    }
}

internal object VoiceTitleFallback {
    private data class Seen(var unreadCount: Int, var ringing: Boolean, var title: String)

    private var attached: Workspace? = null
    private var listener: (() -> Unit)? = null
    private val seen = LinkedHashMap<String, Seen>()
    private var fallbackEvents = 0
    private var lastKind: VoiceNoticeKind? = null

    fun attach(context: Context, workspace: Workspace) {
        if (attached === workspace) return
        listener?.let { old -> attached?.unlisten(old) }
        attached = workspace
        val app = context.applicationContext
        val watch: () -> Unit = { scan(app, workspace) }
        listener = watch
        workspace.listen(watch)
    }

    fun diagnostics(): String = "title fallback alerts: $fallbackEvents${lastKind?.let { " · last: ${it.label}" }.orEmpty()}"

    private fun scan(context: Context, workspace: Workspace) {
        val liveIds = workspace.tabs.filter { Policy.isVoice(it.url) }.mapTo(HashSet()) { it.id }
        seen.keys.retainAll(liveIds)
        workspace.tabs.filter { Policy.isVoice(it.url) }.forEach { tab ->
            val signal = VoiceTitlePolicy.parse(tab.title, tab.url) ?: return@forEach
            val previous = seen[tab.id]
            if (previous == null) {
                seen[tab.id] = Seen(signal.unreadCount, signal.ringing, tab.title)
                return@forEach
            }
            val countIncreased = signal.unreadCount > previous.unreadCount
            val ringingStarted = signal.ringing && !previous.ringing
            previous.unreadCount = signal.unreadCount
            previous.ringing = signal.ringing
            previous.title = tab.title
            if (!countIncreased && !ringingStarted) return@forEach

            val actuallyVisible = workspace.chatVisible && workspace.selectedId == tab.id
            if (actuallyVisible) return@forEach

            tab.unread = true
            workspace.changed(true)
            val kind = if (ringingStarted) VoiceNoticeKind.INCOMING_CALL else signal.kind
            if (!recentNativeVoiceNotification(context, kind)) post(context, tab, kind, signal.unreadCount)
        }
    }

    private fun recentNativeVoiceNotification(context: Context, kind: VoiceNoticeKind): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        return runCatching {
            manager.activeNotifications.any { item ->
                item.notification.channelId == kind.channel && (now - item.postTime) in 0..2500
            }
        }.getOrDefault(false)
    }

    private fun post(context: Context, tab: ChatTab, kind: VoiceNoticeKind, unreadCount: Int) {
        VoiceNotifications.prepare(context)
        if (!VoiceNotifications.notificationsUsable(context, kind.channel)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = when (kind) {
            VoiceNoticeKind.INCOMING_CALL -> "Incoming Google Voice call"
            VoiceNoticeKind.MESSAGE -> "New Google Voice message"
            VoiceNoticeKind.MISSED_CALL -> "Google Voice missed call"
            VoiceNoticeKind.VOICEMAIL -> "New Google Voice voicemail"
            VoiceNoticeKind.OTHER -> "New Google Voice alert"
        }
        val text = when {
            unreadCount > 1 -> "$unreadCount unread items in Google Voice"
            unreadCount == 1 -> "1 unread item in Google Voice"
            else -> "Tap to open Google Voice"
        }
        val id = 5400 + kind.ordinal
        val note = Notification.Builder(context, kind.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(NotificationReturnActivity.pending(context, tab.id, FloatingMode.CHAT))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(if (kind == VoiceNoticeKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        try {
            manager.notify("voice-title:${tab.id}:${kind.name}", id, note)
            fallbackEvents++
            lastKind = kind
        } catch (_: SecurityException) { }
    }
}
