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
import com.mekromn.bubble.ai.model.AiWorkspaceState
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.ai.model.WorkspaceId
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.data.db.AiWorkspacePlacement
import com.mekromn.bubble.data.db.HeadPlacement
import com.mekromn.bubble.heads.model.HeadCollisionResolver
import com.mekromn.bubble.heads.model.HeadPlacementMath
import com.mekromn.bubble.heads.model.NormalizedPoint
import com.mekromn.bubble.heads.model.PixelPoint
import com.mekromn.bubble.heads.overlay.ChatWorkspaceOverlayController
import com.mekromn.bubble.heads.overlay.HeadOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FloatingHeadService : Service() {
    private data class SafeArea(val left: Int, val top: Int, val width: Int, val height: Int)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var app: BubbleApplication
    private val controllers = LinkedHashMap<TabId, HeadOverlayController>()
    private val localPositions = LinkedHashMap<TabId, PixelPoint>()
    private val workspaceControllers = LinkedHashMap<WorkspaceId, ChatWorkspaceOverlayController>()
    private val workspacePositions = LinkedHashMap<WorkspaceId, PixelPoint>()
    private val restoring = mutableSetOf<TabId>()
    private val closing = mutableSetOf<TabId>()
    private val openingWorkspaces = mutableSetOf<WorkspaceId>()
    private var deleteTarget: TextView? = null

    override fun onCreate() {
        super.onCreate()
        app = application as BubbleApplication
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
        if (!startAsForegroundSafely()) {
            stopSelf()
            return
        }
        serviceScope.launch {
            app.runtime.sessions.initialize()
            app.runtime.aiWorkspaces.initialize()
            combine(app.runtime.sessions.state, app.runtime.aiWorkspaces.state) { session, ai -> session.tabs to ai }
                .collect { (tabs, aiState) ->
                    if (aiState.initialized) syncAllOverlays(tabs, aiState)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controllers.values.forEach(HeadOverlayController::removeImmediately)
        workspaceControllers.values.forEach(ChatWorkspaceOverlayController::removeImmediately)
        controllers.clear()
        workspaceControllers.clear()
        localPositions.clear()
        workspacePositions.clear()
        restoring.clear()
        closing.clear()
        openingWorkspaces.clear()
        hideDeleteTarget(immediate = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        serviceScope.launch {
            val tabs = app.runtime.sessions.state.value.tabs
            localPositions.clear()
            tabs.filter { it.presentationState == PresentationState.HEAD }.forEachIndexed { index, tab ->
                positionController(tab.id, app.container.headPlacements.get(tab.id), index)
            }
            val workspaces = app.runtime.aiWorkspaces.state.value.workspaces.filter(ChatWorkspace::collapsedToBubble)
            workspacePositions.clear()
            workspaces.forEachIndexed { index, workspace ->
                positionWorkspaceController(workspace.id, app.runtime.aiWorkspaces.getPlacement(workspace.id), index)
            }
        }
    }

    private suspend fun syncAllOverlays(tabs: List<Tab>, aiState: AiWorkspaceState) {
        if (!Settings.canDrawOverlays(this)) {
            removeAllOverlays()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val aiMemberIds = aiState.workspaces.flatMapTo(mutableSetOf()) { it.tabIds }
        val genericHeads = tabs.filter { it.presentationState == PresentationState.HEAD && it.id !in aiMemberIds }
        val collapsedWorkspaces = aiState.workspaces.filter { workspace ->
            workspace.collapsedToBubble && workspace.tabIds.any { tabId -> tabs.any { it.id == tabId } }
        }
        syncHeads(genericHeads)
        syncWorkspaces(collapsedWorkspaces, tabs)
        if (controllers.isEmpty() && workspaceControllers.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun syncHeads(heads: List<Tab>) {
        val distinctHeads = heads.distinctBy(Tab::id)
        val wanted = distinctHeads.associateBy(Tab::id)
        controllers.keys.filter { it !in wanted.keys }.forEach { id ->
            restoring.remove(id)
            closing.remove(id)
            localPositions.remove(id)
            controllers.remove(id)?.remove()
        }
        distinctHeads.forEachIndexed { index, tab ->
            val existing = controllers[tab.id]
            if (existing != null) existing.update(tab) else {
                val area = safeArea()
                val size = dp(58)
                val local = resolveInitialPosition(app.container.headPlacements.get(tab.id), index, area, size)
                val created = createController(tab, local, area) ?: return@forEachIndexed
                val previous = controllers.putIfAbsent(tab.id, created)
                if (previous != null) {
                    created.removeImmediately()
                    previous.update(tab)
                } else localPositions[tab.id] = local
            }
        }
    }

    private suspend fun syncWorkspaces(workspaces: List<ChatWorkspace>, tabs: List<Tab>) {
        val wanted = workspaces.associateBy(ChatWorkspace::id)
        workspaceControllers.keys.filter { it !in wanted.keys }.forEach { id ->
            openingWorkspaces.remove(id)
            workspacePositions.remove(id)
            workspaceControllers.remove(id)?.remove()
        }
        workspaces.forEachIndexed { index, workspace ->
            val workspaceTabs = workspace.tabIds.mapNotNull { id -> tabs.firstOrNull { it.id == id } }
            val existing = workspaceControllers[workspace.id]
            if (existing != null) existing.update(workspace, workspaceTabs) else {
                val area = safeArea()
                val size = dp(64)
                val local = resolveWorkspaceInitialPosition(
                    app.runtime.aiWorkspaces.getPlacement(workspace.id), index, area, size,
                )
                val created = createWorkspaceController(workspace, workspaceTabs, local, area)
                    ?: return@forEachIndexed
                val previous = workspaceControllers.putIfAbsent(workspace.id, created)
                if (previous != null) {
                    created.removeImmediately()
                    previous.update(workspace, workspaceTabs)
                } else workspacePositions[workspace.id] = local
            }
        }
    }

    private fun resolveInitialPosition(placement: HeadPlacement?, index: Int, area: SafeArea, size: Int): PixelPoint {
        val preferred = placement?.let {
            HeadPlacementMath.denormalize(NormalizedPoint(it.normalizedX, it.normalizedY), area.width, area.height, size, size)
        } ?: defaultPlacement(index, area, size)
        return HeadCollisionResolver.resolve(
            preferred, localPositions.values + workspacePositions.values,
            area.width, area.height, size, size, dp(10),
        )
    }

    private fun resolveWorkspaceInitialPosition(
        placement: AiWorkspacePlacement?, index: Int, area: SafeArea, size: Int,
    ): PixelPoint {
        val preferred = placement?.let {
            HeadPlacementMath.denormalize(NormalizedPoint(it.normalizedX, it.normalizedY), area.width, area.height, size, size)
        } ?: defaultPlacement(index, area, size)
        return HeadCollisionResolver.resolve(
            preferred, localPositions.values + workspacePositions.values,
            area.width, area.height, size, size, dp(10),
        )
    }

    private fun createController(tab: Tab, local: PixelPoint, area: SafeArea): HeadOverlayController? =
        runCatching {
            HeadOverlayController(this, windowManager, tab, area.left + local.x, area.top + local.y, callbacks)
        }.getOrElse {
            if (!Settings.canDrawOverlays(this)) stopSelf()
            null
        }

    private fun createWorkspaceController(
        workspace: ChatWorkspace, tabs: List<Tab>, local: PixelPoint, area: SafeArea,
    ): ChatWorkspaceOverlayController? = runCatching {
        ChatWorkspaceOverlayController(
            this, windowManager, workspace, tabs, area.left + local.x, area.top + local.y, workspaceCallbacks,
        )
    }.getOrElse {
        if (!Settings.canDrawOverlays(this)) stopSelf()
        null
    }

    private fun defaultPlacement(index: Int, area: SafeArea, size: Int): PixelPoint {
        val gap = dp(10)
        val step = size + gap
        val rows = ((area.height - dp(40)).coerceAtLeast(step) / step).coerceAtLeast(1)
        val row = index % rows
        val column = index / rows
        return HeadPlacementMath.clamp(
            PixelPoint(area.width - size - dp(10) - column * step, dp(20) + row * step),
            area.width, area.height, size, size,
        )
    }

    private val workspaceCallbacks = object : ChatWorkspaceOverlayController.Callbacks {
        override fun onOpenWorkspace(workspace: ChatWorkspace) {
            val target = workspace.lastActiveTabId?.takeIf { it in workspace.tabIds }
                ?: workspace.tabIds.firstOrNull() ?: return
            openWorkspaceChat(workspace, target)
        }

        override fun onOpenChat(tabId: TabId) {
            val workspace = app.runtime.aiWorkspaces.workspaceForTab(tabId) ?: return
            openWorkspaceChat(workspace, tabId)
        }

        override fun onDragEnd(workspace: ChatWorkspace, x: Int, y: Int, bubbleSize: Int) {
            serviceScope.launch { persistWorkspacePosition(workspace.id, x, y, bubbleSize) }
        }
    }

    private fun openWorkspaceChat(workspace: ChatWorkspace, tabId: TabId) {
        if (!openingWorkspaces.add(workspace.id)) return
        serviceScope.launch {
            try {
                app.runtime.aiWorkspaces.setCollapsed(workspace.id, false)
                app.runtime.sessions.activate(tabId)
                app.runtime.aiWorkspaces.markRead(tabId)
                runCatching {
                    startActivity(
                        Intent(this@FloatingHeadService, BrowserActivity::class.java)
                            .putExtra(BrowserActivity.EXTRA_RESTORE_TAB_ID, tabId.value)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            } finally {
                openingWorkspaces.remove(workspace.id)
            }
        }
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
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    }
                } finally { restoring.remove(tab.id) }
            }
        }

        override fun onClose(tab: Tab) {
            if (!closing.add(tab.id)) return
            serviceScope.launch {
                try { app.runtime.sessions.close(tab.id) } finally { closing.remove(tab.id) }
            }
        }

        override fun onPinToggle(tab: Tab) { serviceScope.launch { app.runtime.sessions.setPinned(tab.id, !tab.pinned) } }
        override fun onKeepLiveToggle(tab: Tab) { serviceScope.launch { app.runtime.sessions.setKeepRendererAlive(tab.id, !tab.keepRendererAlive) } }
        override fun onDuplicate(tab: Tab) { serviceScope.launch { app.runtime.sessions.duplicateAsHead(tab.id) } }

        override fun onShare(tab: Tab) {
            val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tab.lastCommittedUrl) }
            runCatching { startActivity(Intent.createChooser(share, "Share address").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        override fun onCopy(tab: Tab) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bubble address", tab.lastCommittedUrl))
            Toast.makeText(this@FloatingHeadService, "Address copied", Toast.LENGTH_SHORT).show()
        }

        override fun onInfo(tab: Tab) {
            val live = if (tab.keepRendererAlive) "kept live" else tab.residencyState.name.lowercase()
            Toast.makeText(this@FloatingHeadService, "${tab.title}\n${tab.lastCommittedUrl}\n$live", Toast.LENGTH_LONG).show()
        }

        override fun onDragStart(tab: Tab) { showDeleteTarget(false) }
        override fun onDragMove(tab: Tab, rawX: Float, rawY: Float) { showDeleteTarget(isInsideDeleteTarget(rawX, rawY)) }
        override fun onDragEnd(tab: Tab, rawX: Float, rawY: Float, x: Int, y: Int, headSize: Int) {
            val close = isInsideDeleteTarget(rawX, rawY)
            hideDeleteTarget(false)
            if (close) onClose(tab) else serviceScope.launch { persistPosition(tab.id, x, y, headSize) }
        }
    }

    private suspend fun persistPosition(tabId: TabId, absoluteX: Int, absoluteY: Int, size: Int) {
        val area = safeArea()
        val local = HeadPlacementMath.clamp(PixelPoint(absoluteX - area.left, absoluteY - area.top), area.width, area.height, size, size)
        localPositions[tabId] = local
        val normalized = HeadPlacementMath.normalize(local, area.width, area.height, size, size)
        app.container.headPlacements.save(HeadPlacement(tabId, normalized.x, normalized.y, displayId(), System.currentTimeMillis()))
        controllers[tabId]?.setPosition(area.left + local.x, area.top + local.y)
    }

    private suspend fun persistWorkspacePosition(workspaceId: WorkspaceId, absoluteX: Int, absoluteY: Int, size: Int) {
        val area = safeArea()
        val local = HeadPlacementMath.clamp(PixelPoint(absoluteX - area.left, absoluteY - area.top), area.width, area.height, size, size)
        workspacePositions[workspaceId] = local
        val normalized = HeadPlacementMath.normalize(local, area.width, area.height, size, size)
        app.runtime.aiWorkspaces.savePlacement(
            AiWorkspacePlacement(workspaceId, normalized.x, normalized.y, displayId(), System.currentTimeMillis()),
        )
        workspaceControllers[workspaceId]?.setPosition(area.left + local.x, area.top + local.y)
    }

    private fun positionController(tabId: TabId, placement: HeadPlacement?, index: Int) {
        val controller = controllers[tabId] ?: return
        val area = safeArea()
        val size = controller.headSizePx()
        val preferred = placement?.let {
            HeadPlacementMath.denormalize(NormalizedPoint(it.normalizedX, it.normalizedY), area.width, area.height, size, size)
        } ?: defaultPlacement(index, area, size)
        val local = HeadCollisionResolver.resolve(
            preferred, localPositions.filterKeys { it != tabId }.values + workspacePositions.values,
            area.width, area.height, size, size, dp(10),
        )
        localPositions[tabId] = local
        controller.setPosition(area.left + local.x, area.top + local.y)
    }

    private fun positionWorkspaceController(workspaceId: WorkspaceId, placement: AiWorkspacePlacement?, index: Int) {
        val controller = workspaceControllers[workspaceId] ?: return
        val area = safeArea()
        val size = controller.bubbleSizePx()
        val preferred = placement?.let {
            HeadPlacementMath.denormalize(NormalizedPoint(it.normalizedX, it.normalizedY), area.width, area.height, size, size)
        } ?: defaultPlacement(index, area, size)
        val local = HeadCollisionResolver.resolve(
            preferred, localPositions.values + workspacePositions.filterKeys { it != workspaceId }.values,
            area.width, area.height, size, size, dp(10),
        )
        workspacePositions[workspaceId] = local
        controller.setPosition(area.left + local.x, area.top + local.y)
    }

    private fun removeAllOverlays() {
        controllers.values.forEach(HeadOverlayController::removeImmediately)
        workspaceControllers.values.forEach(ChatWorkspaceOverlayController::removeImmediately)
        controllers.clear(); workspaceControllers.clear(); localPositions.clear(); workspacePositions.clear()
    }

    private fun showDeleteTarget(active: Boolean) {
        val target = deleteTarget ?: TextView(this).apply {
            text = "×"; gravity = Gravity.CENTER; textSize = 34f; setTextColor(Color.WHITE)
            contentDescription = "Drag here to close tab"; alpha = 0f; scaleX = 0.75f; scaleY = 0.75f
        }.also { view ->
            val params = WindowManager.LayoutParams(
                dp(76), dp(76), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(38) }
            if (runCatching { windowManager.addView(view, params) }.isSuccess) {
                deleteTarget = view
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        target.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.rgb(171, 45, 54) else Color.rgb(62, 65, 74))
        }
        val scale = if (active) 1.14f else 1f
        target.animate().scaleX(scale).scaleY(scale).setDuration(90L).start()
    }

    private fun hideDeleteTarget(immediate: Boolean) {
        val target = deleteTarget ?: return
        deleteTarget = null
        target.animate().cancel()
        if (immediate) runCatching { windowManager.removeViewImmediate(target) }
        else target.animate().alpha(0f).scaleX(0.72f).scaleY(0.72f).setDuration(110L)
            .withEndAction { runCatching { windowManager.removeViewImmediate(target) } }.start()
    }

    private fun isInsideDeleteTarget(rawX: Float, rawY: Float): Boolean {
        val metrics = resources.displayMetrics
        val dx = rawX - metrics.widthPixels / 2f
        val dy = rawY - (metrics.heightPixels - dp(76).toFloat())
        val radius = dp(68).toFloat()
        return dx * dx + dy * dy <= radius * radius
    }

    private fun safeArea(): SafeArea = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.currentWindowMetrics
        val bounds: Rect = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        SafeArea(
            bounds.left + insets.left, bounds.top + insets.top,
            (bounds.width() - insets.left - insets.right).coerceAtLeast(1),
            (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
        )
    } else {
        val metrics = resources.displayMetrics
        SafeArea(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    private fun displayId(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId else {
        @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bubble workspace", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps floating browser heads and collapsed AI-chat workspaces available over other apps."
            },
        )
    }

    private fun startAsForegroundSafely(): Boolean = try {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, BrowserActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Bubble workspace active")
            .setContentText("Tap to return to Bubble")
            .setContentIntent(pendingIntent).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
        true
    } catch (_: SecurityException) { false } catch (_: IllegalStateException) { false } catch (_: IllegalArgumentException) { false }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "bubble_heads"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            return try {
                context.startForegroundService(Intent(context, FloatingHeadService::class.java))
                true
            } catch (_: SecurityException) { false } catch (_: IllegalStateException) { false }
        }
    }
}
