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

/** One service owns the visible OR notification-parked live workspace. Hiding never closes tabs. */
class BubbleService : Service() {
    internal var window: FloatingWindow? = null
        private set
    internal var isParked = false
        private set
    private lateinit var workspace: Workspace
    private var pendingMode: FloatingMode? = null
    private var pendingTab: String? = null
    private var acknowledgement: ResultReceiver? = null
    private var lastSummary = ""
    private var stopping = false
    private val changed: () -> Unit = { if (!stopping) { fulfillPending(); updateNotification() } }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); active = this
        workspace = Workspace.get(this)
        workspace.listen(changed)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { workspace.flush(); stopping = true; stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION") val reply = intent?.getParcelableExtra<ResultReceiver>(READY)
        try {
            createChannel()
            val note = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTICE_ID, note)
            if (intent?.action == HIDE) { park(); reply?.send(1, null); return START_NOT_STICKY }
            if (!Settings.canDrawOverlays(this)) { reply?.send(0, null); stopSelf(); return START_NOT_STICKY }
            acknowledgement?.send(0, null)
            acknowledgement = reply
            pendingTab = intent?.getStringExtra(BrowserActivity.EXTRA_TAB)
            pendingMode = runCatching { FloatingMode.valueOf(intent?.getStringExtra(MODE).orEmpty()) }.getOrDefault(FloatingMode.BUBBLE)
            fulfillPending()
        } catch (_: RuntimeException) {
            acknowledgement?.send(0, null); acknowledgement = null
            window?.destroy(); window = null; stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun fulfillPending() {
        if (!workspace.ready || stopping) return
        val mode = pendingMode ?: return
        pendingMode = null
        pendingTab?.let(workspace::select); pendingTab = null
        try {
            isParked = false
            val current = window
            if (current == null) {
                val created = FloatingWindow(this, workspace)
                window = created; created.attach(mode)
            } else when (mode) {
                FloatingMode.BUBBLE -> current.collapse()
                FloatingMode.CHOOSER -> current.showChooser()
                FloatingMode.CHAT -> current.openChat(workspace.selectedId)
            }
            updateNotification()
            acknowledgement?.send(1, null)
        } catch (_: RuntimeException) {
            window?.destroy(); window = null
            acknowledgement?.send(0, null); stopSelf()
        } finally { acknowledgement = null }
    }
    internal fun canPark(): Boolean {
        createChannel()
        return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    internal fun park(): Boolean {
        if (!canPark()) {
            Toast.makeText(this, "Enable Bubble notifications before hiding, so you have a way to restore it.", Toast.LENGTH_LONG).show()
            return false
        }
        // Publish the restore route FIRST. Only then remove both floating windows.
        isParked = true
        try { updateNotification(force = true) }
        catch (_: RuntimeException) { isParked = false; return false }
        pendingMode = null
        window?.destroy(); window = null
        workspace.flush()
        return true
    }
    internal fun releaseForActivity() {
        stopping = true; pendingMode = null
        window?.destroy(); window = null; stopSelf()
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Floating workspace", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Restore the hidden workspace, or stop the background service"
            setShowBadge(false)
        })
    }
    private fun summary(): String {
        val total = workspace.tabs.size
        val generating = workspace.tabs.count { it.generating }
        val unread = workspace.tabs.count { it.unread }
        return "$total tabs · $generating generating · $unread unread"
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, BubbleService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val restore = NotificationReturnActivity.pending(this, null, FloatingMode.BUBBLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isParked) "Bubble hidden · tap to restore" else "Bubble workspace is live")
            .setContentText(summary()).setContentIntent(restore)
            .addAction(Notification.Action.Builder(null, "Show bubble", restore).build())
            .addAction(Notification.Action.Builder(null, "Stop service", stop).build())
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    private fun updateNotification(force: Boolean = false) {
        if (stopping || !workspace.ready) return
        val key = "$isParked:${summary()}"
        if (!force && lastSummary == key) return
        createChannel()
        getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notification())
        lastSummary = key
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); window?.configurationChanged() }
    override fun onDestroy() {
        stopping = true; workspace.unlisten(changed)
        acknowledgement?.send(0, null); acknowledgement = null
        window?.destroy(); window = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (active === this) active = null
        super.onDestroy()
    }
    companion object {
        internal var active: BubbleService? = null
            private set
        const val READY = "bubble.overlay.ready"
        const val MODE = "bubble.overlay.mode"
        const val STOP = "bubble.stop.service"
        const val HIDE = "bubble.hide.overlay"
        const val CHANNEL = "floating-workspace-v2"
        const val NOTICE_ID = 1
    }
}
