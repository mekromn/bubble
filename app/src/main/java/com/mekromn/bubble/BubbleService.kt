package com.mekromn.bubble

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import android.provider.Settings
import android.widget.Toast

/** One foreground owner: expanded chat, resting bubble/edge, or notification-only parking. */
class BubbleService : Service() {
    internal var window: FloatingWindow? = null
        private set
    internal var edge: EdgeHandle? = null
        private set
    internal var isParked = false
        private set
    private lateinit var workspace: Workspace
    private lateinit var access: AccessPreferences
    private var pendingMode: FloatingMode? = null
    private var pendingTab: String? = null
    private var pendingOrigin: WindowBox? = null
    private var forceBubble = false
    private var acknowledgement: ResultReceiver? = null
    private var lastSummary = ""
    private var stopping = false
    private var foreground = false
    private val changed: () -> Unit = { if (!stopping) { fulfillPending(); updateNotification() } }
    private val accessChanged: () -> Unit = {
        if (!stopping) {
            val hadPending = pendingMode != null
            fulfillPending()
            // Loading preferences never creates UI. Only an already visible resting control is replaced.
            if (!hadPending && !isParked && (edge != null || window?.mode == FloatingMode.BUBBLE)) showMinimized()
            updateNotification()
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); active = this
        workspace = Workspace.get(this); access = AccessPreferences.get(this)
        workspace.listen(changed); access.listen(accessChanged)
    }
    override fun startActivity(intent: Intent) {
        val source = window?.takeIf { it.mode == FloatingMode.CHAT }?.geckoView
        if (source != null && intent.component?.className == BrowserActivity::class.java.name) {
            try { FullscreenHandoff.launchFromFloating(this, source, intent); return }
            catch (_: RuntimeException) { }
        }
        super.startActivity(intent)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { workspace.flush(); stopping = true; stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION") val reply = intent?.getParcelableExtra<ResultReceiver>(READY)
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTICE_ID, notification())
            foreground = true
            if (intent?.action == HIDE) {
                val parked = park(); reply?.send(if (parked) 1 else 0, null)
                if (!parked && window == null && edge == null && !isParked) stopSelf()
                return START_NOT_STICKY
            }
            if (!Settings.canDrawOverlays(this)) { reply?.send(0, null); stopSelf(); return START_NOT_STICKY }
            acknowledgement?.send(0, null); acknowledgement = reply
            pendingTab = intent?.getStringExtra(BrowserActivity.EXTRA_TAB)
            forceBubble = intent?.getBooleanExtra(FORCE_BUBBLE, false) == true
            pendingMode = runCatching { FloatingMode.valueOf(intent?.getStringExtra(MODE).orEmpty()) }.getOrDefault(FloatingMode.BUBBLE)
            fulfillPending()
        } catch (_: RuntimeException) {
            acknowledgement?.send(0, null); acknowledgement = null
            removeSurfaces(); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun fulfillPending() {
        if (!workspace.ready || !access.ready || stopping) return
        val mode = pendingMode ?: return
        pendingMode = null
        pendingTab?.let(workspace::select); pendingTab = null
        if (forceBubble) { forceBubble = false; access.update(access.options.copy(enabled = false)) }
        try {
            isParked = false
            if (mode == FloatingMode.BUBBLE) {
                val current = window
                if (current != null && current.mode != FloatingMode.BUBBLE) current.collapse() else showMinimized()
            } else {
                edge?.destroy(); edge = null
                val current = window
                if (current == null) {
                    val created = FloatingWindow(this, workspace); window = created
                    created.attach(mode, pendingOrigin)
                } else if (mode == FloatingMode.CHOOSER) current.showChooser() else current.openChat(workspace.selectedId)
            }
            updateNotification(); acknowledgement?.send(1, null)
        } catch (_: RuntimeException) {
            removeSurfaces(); acknowledgement?.send(0, null)
            if (canPark()) { isParked = true; updateNotification(force = true) } else stopSelf()
        } finally { acknowledgement = null; pendingOrigin = null }
    }
    internal fun prefersEdge(): Boolean = access.ready && access.options.enabled && canPark()
    /** Called after an explicit minimize or the panel's completed exit animation. */
    internal fun showMinimized() {
        if (stopping || !access.ready || !workspace.ready) return
        removeSurfaces(); isParked = false
        if (prefersEdge()) {
            try {
                val handle = EdgeHandle(this, access.options, ::openFromEdge, ::park)
                edge = handle; handle.attach()
                updateNotification(force = true); return
            } catch (_: RuntimeException) { edge?.destroy(); edge = null }
        }
        // Notifications disabled or an overlay failure must not strand an invisible workspace.
        try {
            val bubble = FloatingWindow(this, workspace); window = bubble; bubble.attach(FloatingMode.BUBBLE)
            updateNotification(force = true)
        } catch (_: RuntimeException) {
            removeSurfaces()
            if (canPark()) { isParked = true; updateNotification(force = true) } else stopSelf()
        }
    }
    private fun openFromEdge(selected: Boolean) {
        pendingOrigin = edge?.box
        pendingMode = if (selected) FloatingMode.CHAT else FloatingMode.CHOOSER
        fulfillPending()
    }
    internal fun canPark(): Boolean {
        createChannel()
        val manager = getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    internal fun park(): Boolean {
        if (!canPark()) {
            Toast.makeText(this, "Enable Bubble notifications before hiding, so you have a way to restore it.", Toast.LENGTH_LONG).show(); return false
        }
        val previouslyParked = isParked
        isParked = true
        try { updateNotification(force = true) } catch (_: RuntimeException) { isParked = previouslyParked; return false }
        pendingMode = null; pendingOrigin = null
        removeSurfaces(); workspace.flush(); return true
    }
    private fun removeSurfaces() { edge?.destroy(); edge = null; window?.destroy(); window = null }
    internal fun releaseForActivity() { stopping = true; pendingMode = null; removeSurfaces(); stopSelf() }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Floating workspace", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Restore hidden workspace, switch back to bubble, or stop the service"; setShowBadge(false)
        })
    }
    private fun summary(): String = "${workspace.tabs.size} tabs · ${workspace.tabs.count { it.generating }} generating · ${workspace.tabs.count { it.unread }} unread"
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, BubbleService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val edgeMode = access.ready && access.options.enabled
        val restore = NotificationReturnActivity.pending(this, null, if (!isParked && edge != null) FloatingMode.CHOOSER else FloatingMode.BUBBLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when { isParked -> "Bubble hidden · tap to restore"; edge != null -> "Edge gestures ready · tap for chats"; else -> "Bubble workspace is live" })
            .setContentText(summary()).setContentIntent(restore)
            .addAction(Notification.Action.Builder(null, if (edgeMode) (if (isParked) "Restore edge" else "Open chats") else "Show bubble", restore).build())
        if (edgeMode) builder.addAction(Notification.Action.Builder(null, "Use bubble instead", NotificationReturnActivity.pending(this, null, FloatingMode.BUBBLE, true)).build())
        return builder.addAction(Notification.Action.Builder(null, "Stop service", stop).build())
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE).build()
    }
    private fun updateNotification(force: Boolean = false) {
        if (stopping || !foreground || (!force && !workspace.ready)) return
        val key = "$isParked:${edge != null}:${access.options.enabled}:${summary()}"
        if (!force && lastSummary == key) return
        createChannel(); getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notification()); lastSummary = key
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (edge != null) showMinimized() else window?.configurationChanged()
    }
    override fun onDestroy() {
        stopping = true; workspace.unlisten(changed); access.unlisten(accessChanged)
        acknowledgement?.send(0, null); acknowledgement = null
        removeSurfaces(); stopForeground(STOP_FOREGROUND_REMOVE)
        if (active === this) active = null
        super.onDestroy()
    }
    companion object {
        internal var active: BubbleService? = null
            private set
        const val READY = "bubble.overlay.ready"
        const val MODE = "bubble.overlay.mode"
        const val FORCE_BUBBLE = "bubble.force.bubble"
        const val STOP = "bubble.stop.service"
        const val HIDE = "bubble.hide.overlay"
        const val CHANNEL = "floating-workspace-v2"
        const val NOTICE_ID = 1
    }
}
