package com.mekromn.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * Minimal always-on-top resting control. Expanded chooser/chat UI lives in FloatingBrowserActivity.
 * This keeps the overlay service for the one thing an Activity cannot replace cleanly: persistent
 * access while the user is in other apps.
 */
internal class RestingBubbleWindow(
    private val service: BubbleService,
    private val workspace: Workspace
) {
    private val context: Context = service
    private val manager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dismiss = DismissTarget(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var destroyed = false
    private var dragging = false
    private var held = false
    private var frameQueued = false
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureInitial = WindowBox(0, 0, d(64), d(64))
    private var rectangle = headBox()
    private var target = rectangle
    private val bubble = GlassBubble(context)
    private val root = bubble
    private val hold = Runnable {
        if (!destroyed && !dragging) {
            held = true
            service.openExpanded(FloatingMode.CHAT, workspace.selectedId, rectangle)
        }
    }
    private var params = WindowManager.LayoutParams(
        rectangle.width,
        rectangle.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = rectangle.x
        y = rectangle.y
        title = "Bubble resting access"
    }

    val box: WindowBox get() = rectangle

    fun attach() {
        if (destroyed) return
        root.setOnClickListener { service.openExpanded(FloatingMode.CHOOSER, workspace.selectedId, rectangle) }
        root.setOnLongClickListener {
            service.openExpanded(FloatingMode.CHAT, workspace.selectedId, rectangle)
            true
        }
        root.setOnTouchListener { _, event -> drag(event) }
        ViewCompat.addAccessibilityAction(root, "Open chats") { _, _ ->
            service.openExpanded(FloatingMode.CHOOSER, workspace.selectedId, rectangle); true
        }
        ViewCompat.addAccessibilityAction(root, "Open current chat") { _, _ ->
            service.openExpanded(FloatingMode.CHAT, workspace.selectedId, rectangle); true
        }
        accessibilityMoves(root)
        RenderPolicy.vote(context, root, params)
        manager.addView(root, params)
        render()
    }

    fun render() {
        if (destroyed) return
        bubble.update(
            workspace.tabs.size,
            workspace.tabs.count { it.unread },
            workspace.tabs.any { it.generating }
        )
    }

    fun configurationChanged() {
        if (!destroyed) place(headBox())
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        dismiss.hide(true)
        runCatching { manager.removeView(root) }
    }

    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(hold)
                gestureInitial = rectangle
                gestureX = event.rawX
                gestureY = event.rawY
                dragging = false
                held = false
                main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                val dx = event.rawX - gestureX
                val dy = event.rawY - gestureY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    main.removeCallbacks(hold)
                    if (service.canPark()) dismiss.show(safeArea())
                }
                if (dragging) {
                    var raw = gestureInitial.copy(
                        x = (gestureInitial.x + dx).toInt(),
                        y = (gestureInitial.y + dy).toInt()
                    )
                    if (dismiss.attached) {
                        val before = dismiss.armed
                        val armed = dismiss.track(raw.x + raw.width / 2f, raw.y + raw.height / 2f)
                        if (armed && !before) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (armed) raw = raw.copy(
                            x = (dismiss.centerX - raw.width / 2).toInt(),
                            y = (dismiss.centerY - raw.height / 2).toInt()
                        )
                    }
                    target = WindowGeometry.fit(raw, safeArea())
                    if (!frameQueued) {
                        frameQueued = true
                        root.postOnAnimation {
                            frameQueued = false
                            if (!destroyed && dragging) place(target)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                val completed = event.actionMasked == MotionEvent.ACTION_UP
                val wasDragging = dragging
                if (completed && wasDragging && dismiss.attached) {
                    dismiss.track(
                        gestureInitial.x + (event.rawX - gestureX) + gestureInitial.width / 2f,
                        gestureInitial.y + (event.rawY - gestureY) + gestureInitial.height / 2f
                    )
                }
                val shouldHide = completed && wasDragging && dismiss.armed
                if (wasDragging) place(target)
                dragging = false
                if (shouldHide) {
                    dismiss.hide()
                    service.park()
                } else {
                    dismiss.hide()
                    if (!completed) place(gestureInitial)
                    else if (wasDragging) savePosition()
                    else if (!held) bubble.performClick()
                }
                return true
            }
        }
        return true
    }

    private fun place(box: WindowBox) {
        if (destroyed) return
        val fitted = WindowGeometry.fit(box, safeArea())
        if (rectangle == fitted) return
        rectangle = fitted
        target = fitted
        params.x = fitted.x
        params.y = fitted.y
        params.width = fitted.width
        params.height = fitted.height
        runCatching { manager.updateViewLayout(root, params) }
    }

    private fun savePosition() {
        val safe = safeArea()
        workspace.bubbleX = if (safe.width > rectangle.width)
            (rectangle.x - safe.x).toFloat() / (safe.width - rectangle.width) else .5f
        workspace.bubbleY = if (safe.height > rectangle.height)
            (rectangle.y - safe.y).toFloat() / (safe.height - rectangle.height) else .5f
        workspace.checkpoint()
    }

    private fun accessibilityMoves(view: View) {
        listOf(
            "Move left" to (-1 to 0),
            "Move right" to (1 to 0),
            "Move up" to (0 to -1),
            "Move down" to (0 to 1)
        ).forEach { (name, direction) ->
            ViewCompat.addAccessibilityAction(view, name) { _, _ ->
                place(rectangle.copy(
                    x = rectangle.x + direction.first * d(40),
                    y = rectangle.y + direction.second * d(40)
                ))
                savePosition()
                true
            }
        }
        ViewCompat.addAccessibilityAction(view, "Hide in notification") { _, _ -> service.park() }
    }

    private fun safeArea(): WindowBox {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return WindowBox(
                inset.left + d(4),
                inset.top + d(4),
                (metrics.bounds.width() - inset.left - inset.right - d(8)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - d(8)).coerceAtLeast(1)
            )
        }
        val p = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return WindowBox(d(4), d(28), (p.x - d(8)).coerceAtLeast(1), (p.y - d(60)).coerceAtLeast(1))
    }

    private fun headBox() = WindowGeometry.placed(
        safeArea(), workspace.bubbleX, workspace.bubbleY, d(64), d(64)
    )

    private fun d(n: Int) = Ui.dp(context, n.toFloat())
}
