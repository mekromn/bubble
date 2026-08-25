package com.mekromn.bubble.heads.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.mekromn.bubble.browser.session.Tab
import kotlin.math.abs

class HeadOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    initialTab: Tab,
    initialX: Int,
    initialY: Int,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onRestore(tab: Tab)
        fun onClose(tab: Tab)
        fun onPinToggle(tab: Tab)
        fun onKeepLiveToggle(tab: Tab)
        fun onDuplicate(tab: Tab)
        fun onShare(tab: Tab)
        fun onCopy(tab: Tab)
        fun onInfo(tab: Tab)
        fun onDragStart(tab: Tab)
        fun onDragMove(tab: Tab, rawX: Float, rawY: Float)
        fun onDragEnd(tab: Tab, rawX: Float, rawY: Float, x: Int, y: Int, headSize: Int)
    }

    private var tab: Tab = initialTab
    private val headSizePx = dp(56)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = false
    private var longPressed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = initialX
    private var downY = initialY

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val headView = object : TextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            callbacks.onRestore(tab)
            return true
        }
    }.apply {
        gravity = Gravity.CENTER
        textSize = 22f
        setTextColor(Color.WHITE)
        background = circle(Color.rgb(42, 45, 52))
        elevation = dp(8).toFloat()
        contentDescription = "Floating browser tab"
        layoutParams = LinearLayout.LayoutParams(headSizePx, headSizePx)
    }

    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = rounded(Color.rgb(30, 32, 36), dp(14).toFloat())
        elevation = dp(10).toFloat()
    }

    private val pinButton = actionButton("") { callbacks.onPinToggle(tab) }
    private val liveButton = actionButton("") { callbacks.onKeepLiveToggle(tab) }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = initialX
        y = initialY
    }

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (!dragging) {
                    longPressed = true
                    toggleMenu()
                }
            }
        },
    )

    init {
        root.addView(headView)
        menu.addView(actionButton("Restore") { callbacks.onRestore(tab) })
        menu.addView(pinButton)
        menu.addView(liveButton)
        menu.addView(actionButton("Duplicate") { callbacks.onDuplicate(tab) })
        menu.addView(actionButton("Share") { callbacks.onShare(tab) })
        menu.addView(actionButton("Copy URL") { callbacks.onCopy(tab) })
        menu.addView(actionButton("Info") { callbacks.onInfo(tab) })
        menu.addView(actionButton("Close") { callbacks.onClose(tab) })
        root.addView(menu)
        bindDragGesture()
        update(initialTab)
        windowManager.addView(root, params)
    }

    fun update(updated: Tab) {
        tab = updated
        headView.text = fallbackLabel(updated)
        headView.contentDescription = "${updated.title.ifBlank { "Floating tab" }}. Tap to restore; long press for actions."
        pinButton.text = if (updated.pinned) "Unpin" else "Pin"
        liveButton.text = if (updated.keepRendererAlive) "Stop keeping live" else "Keep live"
    }

    fun setPosition(x: Int, y: Int) {
        params.x = x
        params.y = y
        updateLayout()
    }

    fun remove() {
        runCatching { windowManager.removeViewImmediate(root) }
    }

    fun headSizePx(): Int = headSizePx

    private fun bindDragGesture() {
        headView.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    longPressed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        longPressed = false
                        menu.isVisible = false
                        callbacks.onDragStart(tab)
                    }
                    if (dragging) {
                        params.x = downX + dx.toInt()
                        params.y = downY + dy.toInt()
                        updateLayout()
                        callbacks.onDragMove(tab, event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        callbacks.onDragEnd(
                            tab = tab,
                            rawX = event.rawX,
                            rawY = event.rawY,
                            x = params.x,
                            y = params.y,
                            headSize = headSizePx,
                        )
                    } else if (!longPressed) {
                        view.performClick()
                    }
                    dragging = false
                    longPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        callbacks.onDragEnd(
                            tab = tab,
                            rawX = event.rawX,
                            rawY = event.rawY,
                            x = params.x,
                            y = params.y,
                            headSize = headSizePx,
                        )
                    }
                    dragging = false
                    longPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleMenu() {
        menu.isVisible = !menu.isVisible
        updateLayout()
    }

    private fun updateLayout() {
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        minHeight = dp(40)
        minimumHeight = dp(40)
        setOnClickListener { action() }
    }

    private fun fallbackLabel(tab: Tab): String {
        val value = tab.title.trim().firstOrNull()
            ?: runCatching { tab.lastCommittedUrl.toUri().host?.firstOrNull() }.getOrNull()
            ?: 'B'
        return value.uppercaseChar().toString()
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(90, 255, 255, 255))
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
