package com.mekromn.bubble

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.function.Consumer

/**
 * Fullscreen native black-glass chat switcher.
 *
 * This is deliberately a translucent Dialog window instead of an opaque child of BrowserActivity:
 * Android 12+ can then apply the same compositor background-blur primitive used by Bubble's
 * floating chooser while the real Gecko SurfaceView remains sharp underneath. No screenshot,
 * PixelCopy, polling blur loop, or page bitmap cache is used.
 */
internal class TabTray(
    c: Context,
    select: (String) -> Unit,
    close: (String) -> Unit,
    newChat: () -> Unit,
    private val closeTray: () -> Unit
) : Dialog(c, R.style.Theme_Bubble_GlassOverlay) {
    private val conversations = ConversationList(c, select, close)
    private val count = Ui.text(c, "", 12f, Ui.MUTED)
    private val root = LinearLayout(c)
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null
    private var revealedSelected = ""
    private var blurManager: WindowManager? = null
    private var blurListener: Consumer<Boolean>? = null

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        root.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(d(12), d(8), d(12), d(12))
        }
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), 0, 0, 0) }
        labels.addView(Ui.text(c, "Your chats", 23f, Ui.TEXT, true))
        count.setPadding(0, d(4), 0, 0); labels.addView(count); header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val vault = Ui.text(c, "Vault", 12f, Ui.ACCENT, true).apply {
            gravity = Gravity.CENTER; contentDescription = "Open local Continuity Vault"; isClickable = true; isFocusable = true
            background = Ui.ripple(c, Ui.SURFACE_HIGH, 14f)
            setOnClickListener {
                Workspace.peek()?.let { workspace -> ChatVaultUi.show(this, workspace, workspace::select) }
            }
        }
        header.addView(vault, LinearLayout.LayoutParams(d(64), d(44)).apply { marginEnd = d(4) })
        header.addView(GlyphView(c, "close", "Close Your chats").apply { setOnClickListener { closeTray() } }, LinearLayout.LayoutParams(d(48), d(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, d(60)))
        val search = EditText(c).apply {
            hint = "Find a conversation"; contentDescription = "Find a conversation"; setSingleLine(true)
            textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            setPadding(d(16), 0, d(16), 0); background = Ui.shape(c, Ui.BG, 18f, Ui.LINE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { conversations.search(s.toString()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, d(48)).apply { setMargins(d(8), d(10), d(8), d(8)) })
        root.addView(conversations, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Ui.text(c, "＋  New ChatGPT chat", 15f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(c, Ui.SURFACE_HIGH, 24f); setOnClickListener { newChat() }
        }, LinearLayout.LayoutParams(-1, d(50)).apply { setMargins(d(8), d(8), d(8), 0) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(d(12) + safe.left, d(8) + safe.top, d(12) + safe.right, d(12) + maxOf(safe.bottom, keyboard.bottom))
            insets
        }
        setOnShowListener {
            configureWindow()
            syncBackHandler()
            ViewCompat.requestApplyInsets(root)
        }
        setOnDismissListener {
            releaseBackHandler()
            releaseBlurListener()
            revealedSelected = ""
        }
    }

    fun present() {
        if (!isShowing) show() else configureWindow()
        root.animate().cancel(); root.animate().withEndAction(null)
        if (!ValueAnimator.areAnimatorsEnabled()) { root.alpha = 1f; root.translationY = 0f; return }
        root.alpha = 0f; root.translationY = d(14).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(190L).setInterpolator(Ui.ease).start()
    }

    fun conceal() {
        if (!isShowing) return
        root.animate().cancel(); root.animate().withEndAction(null)
        if (!ValueAnimator.areAnimatorsEnabled()) { dismiss(); return }
        root.animate().alpha(0f).translationY(d(10).toFloat()).setDuration(135L).setInterpolator(Ui.ease)
            .withEndAction { if (isShowing) dismiss() }.start()
    }

    fun closeNow() {
        root.animate().cancel(); root.animate().withEndAction(null)
        if (isShowing) dismiss()
    }

    private fun configureWindow() {
        val win = window ?: return
        if (Build.VERSION.SDK_INT >= 30) win.setDecorFitsSystemWindows(false)
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.setDimAmount(0f)
        val manager = context.getSystemService(WindowManager::class.java)
        val blurAvailable = Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled
        win.setBackgroundDrawable(Ui.glassPanel(context, 0f, blurAvailable))
        if (Build.VERSION.SDK_INT >= 31) {
            // Same compositor primitive and expanded-panel radius family as OverlayGlass.
            win.setBackgroundBlurRadius(Ui.dp(context, 18f).coerceIn(36, 72))
            registerBlurListener(manager)
        }
        win.attributes = win.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            dimAmount = 0f
            flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            title = "Bubble Your chats"
        }
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun registerBlurListener(manager: WindowManager) {
        if (Build.VERSION.SDK_INT < 31 || blurListener != null) return
        val listener = Consumer<Boolean> { enabled ->
            root.post {
                if (isShowing) window?.setBackgroundDrawable(Ui.glassPanel(context, 0f, enabled))
            }
        }
        blurManager = manager; blurListener = listener
        runCatching { manager.addCrossWindowBlurEnabledListener(listener) }
    }

    private fun releaseBlurListener() {
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = blurManager; val listener = blurListener
            if (manager != null && listener != null) runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
        }
        blurManager = null; blurListener = null
    }

    private fun syncBackHandler() {
        if (Build.VERSION.SDK_INT < 33 || !isShowing || backCallback != null) return
        val dispatcher = root.findOnBackInvokedDispatcher() ?: return
        val callback = android.window.OnBackInvokedCallback {
            if (ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            } else closeTray()
        }
        backDispatcher = dispatcher; backCallback = callback
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
    }

    private fun releaseBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        backCallback = null; backDispatcher = null
    }

    fun refresh(workspace: Workspace) {
        val value = "${workspace.tabs.size} tabs · green ready · amber working · red attention · gray suspended"
        if (count.text != value) count.text = value
        conversations.refresh(workspace)
        if (isShowing && revealedSelected != workspace.selectedId) {
            revealedSelected = workspace.selectedId
            conversations.reveal(workspace.selectedId)
        }
    }

    private fun d(n: Int) = Ui.dp(context, n.toFloat())
}
