package com.mekromn.bubble

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import android.provider.Settings

/** One user-visible foreground service for the bubble, chooser and interactive floating chat. */
class BubbleService : Service() {
    internal var window: FloatingWindow? = null
        private set
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); active = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HIDE) { stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION") val receiver = intent?.getParcelableExtra<ResultReceiver>(READY)
        try {
            if (!Settings.canDrawOverlays(this)) { receiver?.send(0, null); stopSelf(); return START_NOT_STICKY }
            val workspace = Workspace.get(this)
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Floating workspace", NotificationManager.IMPORTANCE_LOW))
            val hide = PendingIntent.getService(this, 0, Intent(this, BubbleService::class.java).setAction(HIDE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val note = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bubble workspace").setContentText("Tap the bubble to choose a conversation")
                .setContentIntent(Replies.open(this, workspace.selectedId.takeIf { it.isNotEmpty() }))
                .addAction(Notification.Action.Builder(null, "Hide", hide).build())
                .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, note)
            val requested = runCatching { FloatingMode.valueOf(intent?.getStringExtra(MODE).orEmpty()) }.getOrDefault(FloatingMode.BUBBLE)
            val current = window
            if (current == null) {
                val created = FloatingWindow(this, workspace)
                window = created
                created.attach(requested)
            } else when (requested) {
                FloatingMode.CHOOSER -> current.showChooser()
                FloatingMode.CHAT -> current.openChat(workspace.selectedId)
                FloatingMode.BUBBLE -> current.collapse()
            }
            receiver?.send(1, null)
        } catch (_: RuntimeException) {
            window?.destroy(); window = null
            receiver?.send(0, null); stopSelf()
        }
        return START_NOT_STICKY
    }
    /** Release synchronously before the Activity claims the same GeckoSession. */
    internal fun releaseForActivity() { window?.destroy(); window = null; stopSelf() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); window?.configurationChanged() }
    override fun onDestroy() {
        window?.destroy(); window = null
        if (active === this) active = null
        super.onDestroy()
    }
    companion object {
        internal var active: BubbleService? = null
            private set
        const val READY = "bubble.overlay.ready"
        const val MODE = "bubble.overlay.mode"
        private const val HIDE = "bubble.hide.overlay"
        private const val CHANNEL = "floating-workspace-v2"
    }
}
