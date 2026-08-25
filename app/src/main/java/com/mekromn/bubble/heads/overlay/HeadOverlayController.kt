package com.mekromn.bubble.heads.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.mekromn.bubble.browser.session.Tab
import kotlin.math.abs

private class AccessibleHeadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {
    var restoreAction: (() -> Unit)? = null

    override fun performClick(): Boolean {
        super.performClick()
        restoreAction?.invoke()
        return true
    }
}

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
    private val headSizePx = dp(58)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = false
    private var longPressed = false
    private var removed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = initialX
    private var downY = initialY
    private var headX = initialX
    private var headY = initialY

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private val headView = AccessibleHeadView(context).apply {
        restoreAction = { callbacks.onRestore(tab) }
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Color.WHITE)
        background = circle(Color.rgb(41, 44, 52))
        elevation = dp(10).toFloat()
        contentDescription = "Floating browser tab"
        layoutParams = LinearLayout.LayoutParams(headSizePx, headSizePx)
        isClickable = true
        isFocusable = true
    }

    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(Color.rgb(30, 32, 38), dp(20).toFloat())
        elevation = dp(14).toFloat()
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
                    animateHeadScale(1f)
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
        menu.addView(actionButton("Duplicate head") { callbacks.onDuplicate(tab) })
        menu.addView(actionButton("Share") { callbacks.onShare(tab) })
        menu.addView(actionButton("Copy address") { callbacks.onCopy(tab) })
        menu.addView(actionButton("Tab info") { callbacks.onInfo(tab) })
        menu.addView(actionButton("Close") { callbacks.onClose(tab) })
        root.addView(menu)
        bindDragGesture()
        update(initialTab)
        windowManager.addView(root, params)
        root.alpha = 0f
        root.scaleX = 0.82f
        root.scaleY = 0.82f
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun update(updated: Tab) {
        tab = updated
        headView.text = fallbackLabel(updated)
        headView.contentDescription =
            "${updated.title.ifBlank { "Floating tab" }}. Tap to restore; long press for actions."
        pinButton.text = if (updated.pinned) "Unpin" else "Pin"
        liveButton.text = if (updated.keepRendererAlive) "Stop keeping live" else "Keep live"
    }

    fun setPosition(x: Int, y: Int) {
        headX = x
        headY = y
        if (!menu.isVisible) {
            params.x = x
            params.y = y
            updateLayout()
        }
    }

    fun remove() {
        if (removed) return
        removed = true
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .scaleX(0.78f)
            .scaleY(0.78f)
            .setDuration(130L)
            .withEndAction { runCatching { windowManager.removeViewImmediate(root) } }
            .start()
    }

    fun removeImmediately() {
        if (removed) return
        removed = true
        root.animate().cancel()
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
                    downX = headX
                    downY = headY
                    hideMenu(animated = false)
                    animateHeadScale(0.94f)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        longPressed = false
                        animateHeadScale(1.08f)
                        callbacks.onDragStart(tab)
                    }
                    if (dragging) {
                        headX = downX + dx.toInt()
                        headY = downY + dy.toInt()
                        params.x = headX
                        params.y = headY
                        updateLayout()
                        callbacks.onDragMove(tab, event.rawX, event.rawY)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    animateHeadScale(1f)
                    if (dragging) {
                        callbacks.onDragEnd(
                            tab = tab,
                            rawX = event.rawX,
                            rawY = event.rawY,
                            x = headX,
                            y = headY,
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
                    animateHeadScale(1f)
                    if (dragging) {
                        callbacks.onDragEnd(
                            tab = tab,
                            rawX = event.rawX,
                            rawY = event.rawY,
                            x = headX,
                            y = headY,
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
        if (menu.isVisible) hideMenu(animated = true) else showMenu()
    }

    private fun showMenu() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val estimatedExpandedWidth = dp(260)
        params.x = if (headX + estimatedExpandedWidth > screenWidth) {
            (screenWidth - estimatedExpandedWidth - dp(8)).coerceAtLeast(0)
        } else {
            headX
        }
        params.y = headY
        updateLayout()

        menu.isVisible = true
        menu.alpha = 0f
        menu.scaleX = 0.94f
        menu.scaleY = 0.94f
        menu.translationX = dp(8).toFloat()
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideMenu(animated: Boolean) {
        if (!menu.isVisible) {
            params.x = headX
            params.y = headY
            updateLayout()
            return
        }
        menu.animate().cancel()
        if (!animated) {
            menu.isVisible = false
            menu.alpha = 1f
            menu.scaleX = 1f
            menu.scaleY = 1f
            menu.translationX = 0f
            params.x = headX
            params.y = headY
            updateLayout()
            return
        }
        menu.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationX(dp(6).toFloat())
            .setDuration(110L)
            .withEndAction {
                menu.isVisible = false
                menu.alpha = 1f
                menu.scaleX = 1f
                menu.scaleY = 1f
                menu.translationX = 0f
                params.x = headX
                params.y = headY
                updateLayout()
            }
            .start()
    }

    private fun animateHeadScale(scale: Float) {
        headView.animate().cancel()
        headView.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(100L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun updateLayout() {
        if (removed) return
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        minHeight = dp(44)
        minimumHeight = dp(44)
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
        setStroke(dp(2), Color.argb(96, 255, 255, 255))
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
