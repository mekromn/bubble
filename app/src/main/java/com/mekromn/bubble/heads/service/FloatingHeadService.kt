package com.mekromn.bubble.heads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import com.mekromn.bubble.BrowserActivity
import com.mekromn.bubble.BubbleApplication
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.data.db.HeadPlacement
import com.mekromn.bubble.heads.model.HeadPlacementMath
import com.mekromn.bubble.heads.model.NormalizedPoint
import com.mekromn.bubble.heads.model.PixelPoint
import com.mekromn.bubble.heads.overlay.HeadOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FloatingHeadService : Service() {
    private data class SafeArea(val left: Int, val top: Int, val width: Int, val height: Int)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var app: BubbleApplication
    private val controllers = LinkedHashMap<TabId, HeadOverlayController>()
    private val restoring = mutableSetOf<TabId>()
    private val closing = mutableSetOf<TabId>()
    private var deleteTarget: TextView? = null

    override fun onCreate() {
        super.onCreate()
        app = application as BubbleApplication
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
        startAsForeground()

        serviceScope.launch {
            app.runtime.sessions.initialize()
            app.runtime.sessions.state.collect { state ->
                if (!state.initialized) return@collect
                syncHeads(state.tabs.filter { it.presentationState == PresentationState.HEAD })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Visible heads are restored only from explicit, platform-legal user-visible flows.
        // Never ask Android to resurrect this overlay foreground service after process death.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controllers.values.forEach(HeadOverlayController::removeImmediately)
        controllers.clear()
        restoring.clear()
        closing.clear()
        hideDeleteTarget(immediate = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        serviceScope.launch {
            val heads = app.runtime.sessions.state.value.tabs.filter {
                it.presentationState == PresentationState.HEAD
            }
            heads.forEach { tab ->
                val placement = app.container.headPlacements.get(tab.id)
                positionController(tab.id, placement)
            }
        }
    }

    private suspend fun syncHeads(heads: List<Tab>) {
        if (!Settings.canDrawOverlays(this)) {
            controllers.values.forEach(HeadOverlayController::removeImmediately)
            controllers.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val wanted = heads.distinctBy(Tab::id).associateBy(Tab::id)
        val removed = controllers.keys.filter { it !in wanted.keys }
        removed.forEach { id ->
            restoring.remove(id)
            closing.remove(id)
            controllers.remove(id)?.remove()
        }

        heads.distinctBy(Tab::id).forEachIndexed { index, tab ->
            val existing = controllers[tab.id]
            if (existing != null) {
                existing.update(tab)
            } else {
                val placement = app.container.headPlacements.get(tab.id)
                val created = createController(tab, placement, index) ?: return@forEachIndexed
                val previous = controllers.putIfAbsent(tab.id, created)
                if (previous != null) {
                    // Defensive idempotency: if a second creation ever races in, keep only
                    // the registered controller and immediately remove the duplicate view.
                    created.removeImmediately()
                    previous.update(tab)
                }
            }
        }

        if (controllers.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createController(
        tab: Tab,
        placement: HeadPlacement?,
        index: Int,
    ): HeadOverlayController? {
        val area = safeArea()
        val headSize = dp(58)
        val local = placement?.let {
            HeadPlacementMath.denormalize(
                NormalizedPoint(it.normalizedX, it.normalizedY),
                area.width,
                area.height,
                headSize,
                headSize,
            )
        } ?: defaultPlacement(index, area, headSize)

        return runCatching {
            HeadOverlayController(
                context = this,
                windowManager = windowManager,
                initialTab = tab,
                initialX = area.left + local.x,
                initialY = area.top + local.y,
                callbacks = callbacks,
            )
        }.getOrElse {
            // Overlay permission can be revoked between the permission check and addView().
            // Keep the logical HEAD state intact and stop safely instead of crashing.
            if (!Settings.canDrawOverlays(this)) stopSelf()
            null
        }
    }

    private fun defaultPlacement(index: Int, area: SafeArea, headSize: Int): PixelPoint {
        val gap = dp(10)
        val topInset = dp(20)
        val step = headSize + gap
        val usableHeight = (area.height - topInset - dp(20)).coerceAtLeast(step)
        val rows = (usableHeight / step).coerceAtLeast(1)
        val row = index % rows
        val column = index / rows
        val x = area.width - headSize - dp(10) - column * step
        val y = topInset + row * step
        return HeadPlacementMath.clamp(
            PixelPoint(x, y),
            area.width,
            area.height,
            headSize,
            headSize,
        )
    }

    private val callbacks = object : HeadOverlayController.Callbacks {
        override fun onRestore(tab: Tab) {
            if (!restoring.add(tab.id)) return
            serviceScope.launch {
                try {
                    app.runtime.sessions.activate(tab.id)
                    runCatching {
                        startActivity(
                            Intent(this@FloatingHeadService, BrowserActivity::class.java)
                                .putExtra(BrowserActivity.EXTRA_RESTORE_TAB_ID, tab.id.value)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                    }
                } finally {
                    restoring.remove(tab.id)
                }
            }
        }

        override fun onClose(tab: Tab) {
            if (!closing.add(tab.id)) return
            serviceScope.launch {
                try {
                    app.runtime.sessions.close(tab.id)
                } finally {
                    closing.remove(tab.id)
                }
            }
        }

        override fun onPinToggle(tab: Tab) {
            serviceScope.launch { app.runtime.sessions.setPinned(tab.id, !tab.pinned) }
        }

        override fun onKeepLiveToggle(tab: Tab) {
            serviceScope.launch {
                app.runtime.sessions.setKeepRendererAlive(tab.id, !tab.keepRendererAlive)
            }
        }

        override fun onDuplicate(tab: Tab) {
            serviceScope.launch { app.runtime.sessions.duplicateAsHead(tab.id) }
        }

        override fun onShare(tab: Tab) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, tab.lastCommittedUrl)
            }
            runCatching {
                startActivity(
                    Intent.createChooser(share, "Share address")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        override fun onCopy(tab: Tab) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bubble address", tab.lastCommittedUrl))
            Toast.makeText(this@FloatingHeadService, "Address copied", Toast.LENGTH_SHORT).show()
        }

        override fun onInfo(tab: Tab) {
            val live = if (tab.keepRendererAlive) "kept live" else tab.residencyState.name.lowercase()
            Toast.makeText(
                this@FloatingHeadService,
                "${tab.title}\n${tab.lastCommittedUrl}\n$live",
                Toast.LENGTH_LONG,
            ).show()
        }

        override fun onDragStart(tab: Tab) {
            showDeleteTarget(false)
        }

        override fun onDragMove(tab: Tab, rawX: Float, rawY: Float) {
            showDeleteTarget(isInsideDeleteTarget(rawX, rawY))
        }

        override fun onDragEnd(
            tab: Tab,
            rawX: Float,
            rawY: Float,
            x: Int,
            y: Int,
            headSize: Int,
        ) {
            val close = isInsideDeleteTarget(rawX, rawY)
            hideDeleteTarget(immediate = false)
            if (close) {
                onClose(tab)
            } else {
                serviceScope.launch { persistPosition(tab.id, x, y, headSize) }
            }
        }
    }

    private suspend fun persistPosition(tabId: TabId, absoluteX: Int, absoluteY: Int, headSize: Int) {
        val area = safeArea()
        val local = HeadPlacementMath.clamp(
            PixelPoint(absoluteX - area.left, absoluteY - area.top),
            area.width,
            area.height,
            headSize,
            headSize,
        )
        val normalized = HeadPlacementMath.normalize(
            local,
            area.width,
            area.height,
            headSize,
            headSize,
        )
        app.container.headPlacements.save(
            HeadPlacement(
                tabId = tabId,
                normalizedX = normalized.x,
                normalizedY = normalized.y,
                displayId = displayId(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        controllers[tabId]?.setPosition(area.left + local.x, area.top + local.y)
    }

    private fun positionController(tabId: TabId, placement: HeadPlacement?) {
        val controller = controllers[tabId] ?: return
        val area = safeArea()
        val size = controller.headSizePx()
        val local = placement?.let {
            HeadPlacementMath.denormalize(
                NormalizedPoint(it.normalizedX, it.normalizedY),
                area.width,
                area.height,
                size,
                size,
            )
        } ?: PixelPoint(area.width - size - dp(10), dp(20))
        val clamped = HeadPlacementMath.clamp(local, area.width, area.height, size, size)
        controller.setPosition(area.left + clamped.x, area.top + clamped.y)
    }

    private fun showDeleteTarget(active: Boolean) {
        val target = deleteTarget ?: TextView(this).apply {
            text = "×"
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(Color.WHITE)
            contentDescription = "Drag here to close tab"
            alpha = 0f
            scaleX = 0.75f
            scaleY = 0.75f
        }.also { view ->
            val params = WindowManager.LayoutParams(
                dp(76),
                dp(76),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(38)
            }
            val added = runCatching { windowManager.addView(view, params) }.isSuccess
            if (added) {
                deleteTarget = view
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        target.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.rgb(171, 45, 54) else Color.rgb(62, 65, 74))
        }
        val scale = if (active) 1.14f else 1f
        target.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(90L)
            .start()
    }

    private fun hideDeleteTarget(immediate: Boolean) {
        val target = deleteTarget ?: return
        deleteTarget = null
        target.animate().cancel()
        if (immediate) {
            runCatching { windowManager.removeViewImmediate(target) }
            return
        }
        target.animate()
            .alpha(0f)
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(110L)
            .withEndAction { runCatching { windowManager.removeViewImmediate(target) } }
            .start()
    }

    private fun isInsideDeleteTarget(rawX: Float, rawY: Float): Boolean {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels - dp(76).toFloat()
        val radius = dp(68).toFloat()
        val dx = rawX - centerX
        val dy = rawY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun safeArea(): SafeArea {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds: Rect = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            SafeArea(
                left = bounds.left + insets.left,
                top = bounds.top + insets.top,
                width = (bounds.width() - insets.left - insets.right).coerceAtLeast(1),
                height = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
            )
        } else {
            val metrics = resources.displayMetrics
            SafeArea(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        }
    }

    private fun displayId(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.displayId
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.displayId
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Floating browser heads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps your floating browser tab heads available over other apps."
            },
        )
    }

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Bubble heads active")
            .setContentText("Tap to return to Bubble")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "bubble_heads"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            return try {
                context.startForegroundService(Intent(context, FloatingHeadService::class.java))
                true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalStateException) {
                // Includes the API 31+ ForegroundServiceStartNotAllowedException subclass
                // without introducing a direct new-API type reference on minSdk 26.
                false
            }
        }
    }
}
