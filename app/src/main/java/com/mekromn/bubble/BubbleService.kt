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

/**
 * Persistent owner for workspace lifetime and minimized access. Expanded chooser/chat UI is hosted
 * by FloatingBrowserActivity so the real interactive floating browser receives Activity scheduling.
 */
class BubbleService : Service() {
    /** Legacy field retained for transition/compatibility callers; Build 161 no longer creates it. */
    internal var window: FloatingWindow? = null
        private set
    internal var edge: EdgeHandle? = null
        private set
    internal var isParked = false
        private set

    private var bubbleWindow: RestingBubbleWindow? = null
    private var activityHost: FloatingBrowserActivity? = null
    private var activityEnding = false

    private lateinit var workspace: Workspace
    private lateinit var access: AccessPreferences
    private val main = Handler(Looper.getMainLooper())
    private var pendingMode: FloatingMode? = null
    private var pendingTab: String? = null
    private var pendingOrigin: WindowBox? = null
    private var forceBubble = false
    private var acknowledgement: ResultReceiver? = null
    private var lastNotificationState: NotificationState? = null
    private var channelReady = false
    private var notificationPending = false
    private var stopping = false
    private var foreground = false

    private val notificationTask = Runnable {
        notificationPending = false
        if (!stopping) updateNotification()
    }
    private val changed: () -> Unit = {
        if (!stopping) {
            fulfillPending()
            bubbleWindow?.render()
            scheduleNotificationUpdate()
        }
    }
    private val accessChanged: () -> Unit = {
        if (!stopping) {
            val hadPending = pendingMode != null
            fulfillPending()
            if (!hadPending && activityHost == null && !isParked && (edge != null || bubbleWindow != null)) showMinimized()
            updateNotification(force = true)
        }
    }

    private data class NotificationState(
        val parked: Boolean,
        val edgeVisible: Boolean,
        val bubbleVisible: Boolean,
        val expandedVisible: Boolean,
        val edgeMode: Boolean,
        val total: Int,
        val generating: Int,
        val unread: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        active = this
        workspace = Workspace.get(this)
        access = AccessPreferences.get(this)
        workspace.listen(changed)
        access.listen(accessChanged)
    }

    override fun startActivity(intent: Intent) {
        val host = activityHost
        val source = host?.takeIf { it.currentMode == FloatingMode.CHAT }?.geckoView
        if (source != null && intent.component?.className == BrowserActivity::class.java.name) {
            try {
                FullscreenHandoff.launchFromFloating(this, source, host.pageHost, intent)
                return
            } catch (_: RuntimeException) { }
        }
        super.startActivity(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            workspace.flush()
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION") val reply = intent?.getParcelableExtra<ResultReceiver>(READY)
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTICE_ID, notification())
            foreground = true

            if (intent?.action == HIDE) {
                val parked = park()
                reply?.send(if (parked) 1 else 0, null)
                if (!parked && bubbleWindow == null && edge == null && activityHost == null && !isParked) stopSelf()
                return START_NOT_STICKY
            }
            if (!Settings.canDrawOverlays(this)) {
                reply?.send(0, null)
                stopSelf()
                return START_NOT_STICKY
            }

            acknowledgement?.send(0, null)
            acknowledgement = reply
            pendingTab = intent?.getStringExtra(BrowserActivity.EXTRA_TAB)
            forceBubble = intent?.getBooleanExtra(FORCE_BUBBLE, false) == true
            pendingMode = runCatching { FloatingMode.valueOf(intent?.getStringExtra(MODE).orEmpty()) }
                .getOrDefault(FloatingMode.BUBBLE)
            fulfillPending()
        } catch (_: RuntimeException) {
            acknowledgement?.send(0, null)
            acknowledgement = null
            removeSurfaces()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    internal fun openExpanded(mode: FloatingMode, tabId: String, origin: WindowBox? = null) {
        if (stopping || !workspace.ready || !access.ready) return
        pendingTab = tabId
        pendingOrigin = origin
        pendingMode = if (mode == FloatingMode.BUBBLE) FloatingMode.CHOOSER else mode
        fulfillPending()
    }

    private fun fulfillPending() {
        if (!workspace.ready || !access.ready || stopping) return
        val requested = pendingMode ?: return
        pendingMode = null
        pendingTab?.let(workspace::select)
        pendingTab = null
        if (forceBubble) {
            forceBubble = false
            access.update(access.options.copy(enabled = false))
        }
        try {
            isParked = false
            if (requested == FloatingMode.BUBBLE) {
                showMinimized()
                acknowledgement?.send(1, null)
                acknowledgement = null
                pendingOrigin = null
                return
            }

            edge?.destroy(); edge = null
            bubbleWindow?.destroy(); bubbleWindow = null
            window?.destroy(); window = null

            val existing = activityHost
            if (existing != null && !existing.isFinishing) {
                existing.present(requested)
                acknowledgement?.send(1, null)
                acknowledgement = null
                pendingOrigin = null
                updateNotification(force = true)
                return
            }

            val origin = pendingOrigin
            val launch = Intent(this, FloatingBrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(FloatingBrowserActivity.EXTRA_MODE, requested.name)
                putExtra(FloatingBrowserActivity.EXTRA_TAB, workspace.selectedId)
                origin?.let {
                    putExtra(FloatingBrowserActivity.EXTRA_ORIGIN_X, it.x)
                    putExtra(FloatingBrowserActivity.EXTRA_ORIGIN_Y, it.y)
                    putExtra(FloatingBrowserActivity.EXTRA_ORIGIN_W, it.width)
                    putExtra(FloatingBrowserActivity.EXTRA_ORIGIN_H, it.height)
                }
            }
            pendingOrigin = null
            super.startActivity(launch)
            // Acknowledgement is completed by activityHostReady only after the real Activity window,
            // single ViewRoot and Gecko host exist.
        } catch (_: RuntimeException) {
            pendingOrigin = null
            acknowledgement?.send(0, null)
            acknowledgement = null
            if (canPark()) {
                isParked = true
                updateNotification(force = true)
            } else stopSelf()
        }
    }

    internal fun activityHostReady(activity: FloatingBrowserActivity) {
        if (stopping) {
            activity.finishAndRemoveTask()
            return
        }
        activityEnding = false
        activityHost = activity
        isParked = false
        acknowledgement?.send(1, null)
        acknowledgement = null
        updateNotification(force = true)
    }

    internal fun activityHostFailed(activity: FloatingBrowserActivity, reason: String) {
        if (activityHost === activity) activityHost = null
        acknowledgement?.send(0, null)
        acknowledgement = null
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        if (!stopping) showMinimized()
    }

    internal fun activityHostDestroyed(activity: FloatingBrowserActivity) {
        if (activityHost === activity) activityHost = null
        val intentional = activityEnding || stopping
        activityEnding = false
        if (!intentional && !isParked && bubbleWindow == null && edge == null && !stopping) {
            main.post { if (!stopping && activityHost == null && !isParked) showMinimized() }
        }
        updateNotification(force = true)
    }

    internal fun minimizeFromActivity(activity: FloatingBrowserActivity) {
        if (activityHost !== activity || stopping) return
        activityEnding = true
        activityHost = null
        runCatching { activity.finishAndRemoveTask() }
        showMinimized()
    }

    internal fun parkFromActivity(activity: FloatingBrowserActivity): Boolean {
        if (activityHost !== activity || stopping) return false
        activityEnding = true
        activityHost = null
        runCatching { activity.finishAndRemoveTask() }
        return park()
    }

    internal fun prefersEdge(): Boolean = access.ready && access.options.enabled && canPark()

    /** Called after an explicit minimize or completed Activity-host exit animation. */
    internal fun showMinimized() {
        if (stopping || !access.ready || !workspace.ready) return
        finishActivityHost()
        edge?.destroy(); edge = null
        bubbleWindow?.destroy(); bubbleWindow = null
        window?.destroy(); window = null
        isParked = false

        if (prefersEdge()) {
            try {
                val handle = EdgeHandle(this, access.options, ::openFromEdge, ::park)
                edge = handle
                handle.attach()
                updateNotification(force = true)
                return
            } catch (_: RuntimeException) {
                edge?.destroy(); edge = null
            }
        }

        try {
            val bubble = RestingBubbleWindow(this, workspace)
            bubbleWindow = bubble
            bubble.attach()
            updateNotification(force = true)
        } catch (_: RuntimeException) {
            bubbleWindow?.destroy(); bubbleWindow = null
            if (canPark()) {
                isParked = true
                updateNotification(force = true)
            } else stopSelf()
        }
    }

    private fun openFromEdge(selected: Boolean) {
        val origin = edge?.box
        openExpanded(if (selected) FloatingMode.CHAT else FloatingMode.CHOOSER, workspace.selectedId, origin)
    }

    /** Global transparency changes update the Activity window in place; sessions never restart. */
    internal fun visualEffectsChanged() {
        if (stopping || isParked || !workspace.ready || !access.ready) return
        activityHost?.let {
            it.visualEffectsChanged()
            updateNotification(force = true)
            return
        }
        if (edge != null || bubbleWindow != null) showMinimized()
    }

    internal fun canPark(): Boolean {
        createChannel()
        val manager = getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    internal fun park(): Boolean {
        if (!canPark()) {
            Toast.makeText(
                this,
                "Enable Bubble notifications before hiding, so you have a way to restore it.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val previouslyParked = isParked
        isParked = true
        try { updateNotification(force = true) }
        catch (_: RuntimeException) {
            isParked = previouslyParked
            return false
        }
        pendingMode = null
        pendingOrigin = null
        removeSurfaces()
        workspace.flush()
        return true
    }

    private fun finishActivityHost() {
        val host = activityHost ?: return
        activityEnding = true
        activityHost = null
        runCatching { host.finishAndRemoveTask() }
    }

    private fun removeSurfaces() {
        finishActivityHost()
        edge?.destroy(); edge = null
        bubbleWindow?.destroy(); bubbleWindow = null
        window?.destroy(); window = null
    }

    internal fun releaseForActivity() {
        stopping = true
        pendingMode = null
        removeSurfaces()
        stopSelf()
    }

    private fun createChannel() {
        if (channelReady) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating workspace", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Restore hidden workspace, switch back to bubble, or stop the service"
                setShowBadge(false)
            }
        )
        channelReady = true
    }

    private fun notificationState(): NotificationState {
        var generating = 0
        var unread = 0
        for (tab in workspace.tabs) {
            if (tab.generating) generating++
            if (tab.unread) unread++
        }
        return NotificationState(
            parked = isParked,
            edgeVisible = edge != null,
            bubbleVisible = bubbleWindow != null,
            expandedVisible = activityHost != null,
            edgeMode = access.ready && access.options.enabled,
            total = workspace.tabs.size,
            generating = generating,
            unread = unread
        )
    }

    private fun summary(state: NotificationState): String =
        "${state.total} tabs · ${state.generating} generating · ${state.unread} unread"

    private fun notification(state: NotificationState = notificationState()): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restoreMode = when {
            state.expandedVisible -> FloatingMode.CHAT
            !state.parked && state.edgeVisible -> FloatingMode.CHOOSER
            else -> FloatingMode.BUBBLE
        }
        val restore = NotificationReturnActivity.pending(this, null, restoreMode)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when {
                state.parked -> "Bubble hidden · tap to restore"
                state.expandedVisible -> "Floating browser is live"
                state.edgeVisible -> "Edge gestures ready · tap for chats"
                else -> "Bubble workspace is live"
            })
            .setContentText(summary(state))
            .setContentIntent(restore)
            .addAction(Notification.Action.Builder(
                null,
                if (state.edgeMode) (if (state.parked) "Restore edge" else "Open chats") else "Show bubble",
                restore
            ).build())
        if (state.edgeMode) {
            builder.addAction(Notification.Action.Builder(
                null,
                "Use bubble instead",
                NotificationReturnActivity.pending(this, null, FloatingMode.BUBBLE, true)
            ).build())
        }
        return builder.addAction(Notification.Action.Builder(null, "Stop service", stop).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun scheduleNotificationUpdate() {
        if (!foreground || notificationPending || stopping) return
        notificationPending = true
        main.postDelayed(notificationTask, NOTIFICATION_DEBOUNCE_MS)
    }

    private fun updateNotification(force: Boolean = false) {
        if (stopping || !foreground || (!force && !workspace.ready)) return
        if (force && notificationPending) {
            main.removeCallbacks(notificationTask)
            notificationPending = false
        }
        val state = notificationState()
        if (!force && lastNotificationState == state) return
        createChannel()
        getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notification(state))
        lastNotificationState = state
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (activityHost == null) {
            if (edge != null) showMinimized() else bubbleWindow?.configurationChanged()
        }
    }

    override fun onDestroy() {
        stopping = true
        workspace.unlisten(changed)
        access.unlisten(accessChanged)
        main.removeCallbacksAndMessages(null)
        notificationPending = false
        acknowledgement?.send(0, null)
        acknowledgement = null
        removeSurfaces()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        internal var active: BubbleService? = null
            private set
        private const val NOTIFICATION_DEBOUNCE_MS = 250L
        const val READY = "bubble.overlay.ready"
        const val MODE = "bubble.overlay.mode"
        const val FORCE_BUBBLE = "bubble.force.bubble"
        const val STOP = "bubble.stop.service"
        const val HIDE = "bubble.hide.overlay"
        const val CHANNEL = "floating-workspace-v2"
        const val NOTICE_ID = 1
    }
}
