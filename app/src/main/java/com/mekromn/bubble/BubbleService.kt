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
        val currentWindow = window?.takeIf { it.mode == FloatingMode.CHAT }
        val source = currentWindow?.geckoView
        if (source != null && intent.component?.className == BrowserActivity::class.java.name) {
            try { FullscreenHandoff.launchFromFloating(this, source, currentWindow.pageHost, intent); return }
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
            window?.destroy(); window = null
            if (canPark()) { isParked = true; updateNotification(force = true) } else stopSelf()
        }
    }
    private fun openFromEdge() {
        if (stopping || !workspace.ready) return
        edge?.destroy(); edge = null; isParked = false
        try {
            val floating = FloatingWindow(this, workspace); window = floating; floating.attach(FloatingMode.CHOOSER)
            updateNotification(force = true)
        } catch (_: RuntimeException) { showMinimized() }
    }
    internal fun park(): Boolean {
        if (stopping) return false
        removeSurfaces()
        isParked = true
        updateNotification(force = true)
        return foreground
    }
    internal fun canPark(): Boolean = foreground && notificationPermissionGranted()
    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    internal fun releaseForActivity() { removeSurfaces(); isParked = false; updateNotification(force = true) }
    private fun removeSurfaces() {
        window?.destroy(); window = null
        edge?.destroy(); edge = null
    }
    override fun onDestroy() {
        stopping = true; workspace.unlisten(changed); access.unlisten(accessChanged); removeSurfaces(); active = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); window?.configurationChanged() }
    private fun updateNotification(force: Boolean = false) {
        if (!foreground) return
        val summary = when {
            isParked -> "Workspace hidden · tap to restore"
            window?.mode == FloatingMode.CHAT -> "Floating chat open"
            window?.mode == FloatingMode.CHOOSER -> "Conversation chooser open"
            edge != null -> "Edge access ready"
            else -> "Bubble ready"
        }
        if (!force && summary == lastSummary) return
        lastSummary = summary
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notification(summary)) }
    }
    private fun notification(summary: String = lastSummary.ifBlank { "Bubble ready" }): Notification {
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BubbleService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_notification).setContentTitle("Bubble").setContentText(summary)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Bubble workspace",NotificationManager.IMPORTANCE_LOW).apply { description="Keeps floating browser tabs available" })
    }
    companion object {
        @Volatile var active: BubbleService? = null
        const val READY="bubble.ready"; const val MODE="bubble.mode"; const val FORCE_BUBBLE="bubble.force.bubble"; const val HIDE="bubble.hide"; const val STOP="bubble.stop"
        private const val CHANNEL="bubble.workspace"; private const val NOTICE_ID=5101
    }
}
