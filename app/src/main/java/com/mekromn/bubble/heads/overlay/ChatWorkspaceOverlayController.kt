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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.mekromn.bubble.R
import com.mekromn.bubble.ai.model.AiChatState
import com.mekromn.bubble.ai.model.AiChatTabStatus
import com.mekromn.bubble.ai.model.ChatWorkspace
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import kotlin.math.abs

private class AccessibleWorkspaceBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var openAction: (() -> Unit)? = null
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_chatgpt_workspace)
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val badge = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(Color.WHITE)
        minWidth = dp(22)
        minHeight = dp(20)
        setPadding(dp(5), 0, dp(5), 0)
        isVisible = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        isClickable = true
        isFocusable = true
        background = circle(Color.rgb(30, 31, 34))
        elevation = dp(12).toFloat()
        addView(
            icon,
            LayoutParams(dp(38), dp(38), Gravity.CENTER),
        )
        addView(
            badge,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(20), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(1)
                bottomMargin = dp(1)
            },
        )
    }

    fun bind(workspace: ChatWorkspace) {
        background = circle(
            when {
                workspace.unreadCompletedCount > 0 -> Color.rgb(24, 104, 71)
                workspace.generatingCount > 0 -> Color.rgb(55, 62, 75)
                workspace.recoveringCount > 0 -> Color.rgb(83, 68, 45)
                else -> Color.rgb(30, 31, 34)
            },
        )
        val badgeText = when {
            workspace.unreadCompletedCount > 0 -> workspace.unreadCompletedCount.coerceAtMost(99).toString()
            workspace.generatingCount > 0 -> workspace.generatingCount.coerceAtMost(99).toString()
            workspace.recoveringCount > 0 -> "↻"
            else -> null
        }
        badge.isVisible = badgeText != null
        badge.text = badgeText.orEmpty()
        badge.background = pill(
            if (workspace.unreadCompletedCount > 0) Color.rgb(22, 163, 106)
            else Color.rgb(83, 88, 101),
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        openAction?.invoke()
        return true
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), Color.argb(110, 255, 255, 255))
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(color)
        setStroke(dp(1), Color.argb(120, 255, 255, 255))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class ChatWorkspaceOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    initialWorkspace: ChatWorkspace,
    initialTabs: List<Tab>,
    initialX: Int,
    initialY: Int,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onOpenWorkspace(workspace: ChatWorkspace)
        fun onOpenChat(tabId: TabId)
        fun onDragEnd(workspace: ChatWorkspace, x: Int, y: Int, bubbleSize: Int)
    }

    private var workspace = initialWorkspace
    private var tabsById = initialTabs.associateBy(Tab::id)
    private val bubbleSizePx = dp(64)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = false
    private var longPressed = false
    private var removed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = initialX
    private var downY = initialY
    private var bubbleX = initialX
    private var bubbleY = initialY

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private val bubbleView = AccessibleWorkspaceBubbleView(context).apply {
        openAction = { callbacks.onOpenWorkspace(workspace) }
        layoutParams = LinearLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
    }

    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(Color.rgb(27, 29, 34), dp(20).toFloat())
        elevation = dp(14).toFloat()
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
                    animateScale(1f)
                    toggleMenu()
                }
            }
        },
    )

    init {
        root.addView(bubbleView)
        root.addView(menu)
        bindDragGesture()
        update(initialWorkspace, initialTabs)
        windowManager.addView(root, params)
        root.alpha = 0f
        root.scaleX = 0.82f
        root.scaleY = 0.82f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun update(updatedWorkspace: ChatWorkspace, tabs: List<Tab>) {
        workspace = updatedWorkspace
        tabsById = tabs.associateBy(Tab::id)
        bubbleView.bind(updatedWorkspace)
        bubbleView.contentDescription = buildString {
            append("ChatGPT workspace. ${updatedWorkspace.tabIds.size} chats")
            if (updatedWorkspace.generatingCount > 0) append(", ${updatedWorkspace.generatingCount} generating")
            if (updatedWorkspace.unreadCompletedCount > 0) append(", ${updatedWorkspace.unreadCompletedCount} unread replies")
            append(". Tap to open; long press to choose a chat.")
        }
        rebuildMenu()
    }

    fun setPosition(x: Int, y: Int) {
        bubbleX = x
        bubbleY = y
        if (!menu.isVisible) {
            params.x = x
            params.y = y
            updateLayout()
        }
    }

    fun bubbleSizePx(): Int = bubbleSizePx

    fun remove() {
        if (removed) return
        removed = true
        root.animate().cancel()
        root.animate().alpha(0f).scaleX(0.78f).scaleY(0.78f).setDuration(130L)
            .withEndAction { runCatching { windowManager.removeViewImmediate(root) } }.start()
    }

    fun removeImmediately() {
        if (removed) return
        removed = true
        root.animate().cancel()
        runCatching { windowManager.removeViewImmediate(root) }
    }

    private fun rebuildMenu() {
        menu.removeAllViews()
        menu.addView(actionButton("Open ChatGPT workspace") { callbacks.onOpenWorkspace(workspace) })
        workspace.chats
            .sortedWith(
                compareByDescending<AiChatTabStatus> { it.state == AiChatState.COMPLETE_UNREAD }
                    .thenByDescending { it.state == AiChatState.GENERATING }
                    .thenByDescending { it.lastStateChangeAt },
            )
            .forEach { chat ->
                val tab = tabsById[chat.tabId]
                val title = tab?.title?.takeIf { it.isNotBlank() && it != "New tab" }
                    ?: chat.conversationTitle ?: "ChatGPT chat"
                val prefix = when (chat.state) {
                    AiChatState.GENERATING -> "… "
                    AiChatState.COMPLETE_UNREAD -> "● "
                    AiChatState.RECOVERING -> "↻ "
                    AiChatState.ERROR -> "! "
                    else -> ""
                }
                menu.addView(actionButton((prefix + title).take(52)) { callbacks.onOpenChat(chat.tabId) })
            }
    }

    private fun bindDragGesture() {
        bubbleView.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    longPressed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = bubbleX
                    downY = bubbleY
                    hideMenu(animated = false)
                    animateScale(0.94f)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        longPressed = false
                        animateScale(1.08f)
                    }
                    if (dragging) {
                        bubbleX = downX + dx.toInt()
                        bubbleY = downY + dy.toInt()
                        params.x = bubbleX
                        params.y = bubbleY
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    animateScale(1f)
                    if (dragging) callbacks.onDragEnd(workspace, bubbleX, bubbleY, bubbleSizePx)
                    else if (!longPressed) view.performClick()
                    dragging = false
                    longPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    animateScale(1f)
                    if (dragging) callbacks.onDragEnd(workspace, bubbleX, bubbleY, bubbleSizePx)
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
        val estimatedExpandedWidth = dp(300)
        params.x = if (bubbleX + estimatedExpandedWidth > screenWidth) {
            (screenWidth - estimatedExpandedWidth - dp(8)).coerceAtLeast(0)
        } else bubbleX
        params.y = bubbleY
        updateLayout()
        menu.isVisible = true
        menu.alpha = 0f
        menu.scaleX = 0.94f
        menu.scaleY = 0.94f
        menu.translationX = dp(8).toFloat()
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setDuration(160L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideMenu(animated: Boolean) {
        if (!menu.isVisible) {
            params.x = bubbleX
            params.y = bubbleY
            updateLayout()
            return
        }
        menu.animate().cancel()
        if (!animated) {
            menu.isVisible = false
            resetMenuTransform()
            params.x = bubbleX
            params.y = bubbleY
            updateLayout()
            return
        }
        menu.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationX(dp(8).toFloat())
            .setDuration(110L).withEndAction {
                menu.isVisible = false
                resetMenuTransform()
                params.x = bubbleX
                params.y = bubbleY
                updateLayout()
            }.start()
    }

    private fun resetMenuTransform() {
        menu.alpha = 1f
        menu.scaleX = 1f
        menu.scaleY = 1f
        menu.translationX = 0f
    }

    private fun updateLayout() {
        if (!removed) runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun animateScale(scale: Float) {
        root.animate().cancel()
        root.animate().scaleX(scale).scaleY(scale).setDuration(90L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        background = null
        minWidth = dp(220)
        minHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener {
            hideMenu(animated = true)
            action()
        }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
