package com.mekromn.bubble

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.transition.Fade
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import java.lang.ref.WeakReference

/** Anchored, bounded native popup shared by Activity and overlay hosts. No extra Activity,
 * fullscreen transparent window, screenshot or GeckoSession is created for a quick menu. */
internal class QuickPanel private constructor(val anchor: View, private val workspace: Workspace,
    title: String, heightDp: Int) {
    val body = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
    val content = LinearLayout(anchor.context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.shape(context, Ui.SURFACE, 24f, Ui.LINE)
        setPadding(dp(8), dp(6), dp(8), dp(8)); isFocusableInTouchMode = true
    }
    private val popup = PopupWindow(anchor.context)
    private val cleanups = ArrayList<() -> Unit>()
    private var cleaned = false
    val showing: Boolean get() = popup.isShowing && !cleaned
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { dismiss() }
    }
    init {
        val header = LinearLayout(anchor.context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(anchor.context, title, 17f, Ui.TEXT, true).apply {
            setPadding(dp(10), 0, dp(6), 0); maxLines = 2
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(GlyphView(anchor.context, "close", "Close quick menu").apply { setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val safe = Rect(); anchor.getWindowVisibleDisplayFrame(safe)
        if (safe.width() <= 0 || safe.height() <= 0) safe.set(0, 0, anchor.resources.displayMetrics.widthPixels, anchor.resources.displayMetrics.heightPixels)
        val width = minOf(dp(380), safe.width() - dp(16)).coerceAtLeast(1)
        val height = minOf(dp(heightDp), (safe.height() * .78f).toInt()).coerceAtLeast(1)
        popup.contentView = content; popup.width = width; popup.height = height
        popup.isFocusable = true; popup.isOutsideTouchable = true; popup.elevation = dp(14).toFloat()
        popup.setBackgroundDrawable(Ui.shape(anchor.context, Ui.SURFACE, 24f, Ui.LINE))
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        popup.enterTransition = Fade().apply { duration = 140 }
        popup.exitTransition = Fade().apply { duration = 100 }
        popup.setOnDismissListener { cleanup() }
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val x = (location[0] + anchor.width - width).coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left))
        val above = location[1] - height - dp(6)
        val below = location[1] + anchor.height + dp(6)
        val y = (if (above >= safe.top || below + height > safe.bottom) above else below)
            .coerceIn(safe.top, (safe.bottom - height).coerceAtLeast(safe.top))
        anchor.addOnAttachStateChangeListener(detach)
        try {
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
            current = WeakReference(this); workspace.quickMenuVisible = true
            RenderPolicy.vote(anchor.context, content); content.requestFocus()
        } catch (_: RuntimeException) { cleanup() }
    }
    fun onClose(action: () -> Unit) { if (cleaned) action() else cleanups += action }
    fun dismiss() { if (popup.isShowing) popup.dismiss() else cleanup() }
    fun finish(action: () -> Unit) {
        dismiss()
        Handler(Looper.getMainLooper()).post { if (anchor.isAttachedToWindow) action() }
    }
    private fun cleanup() {
        if (cleaned) return
        cleaned = true; anchor.removeOnAttachStateChangeListener(detach)
        if (current.get() === this) current.clear()
        workspace.quickMenuVisible = current.get()?.showing == true
        val actions = cleanups.toList(); cleanups.clear(); actions.forEach { it() }
        Handler(Looper.getMainLooper()).post { workspace.applyPolicy(); workspace.changed() }
    }
    private fun dp(n: Int) = Ui.dp(anchor.context, n.toFloat())
    companion object {
        private var current = WeakReference<QuickPanel>(null)
        fun open(anchor: View, workspace: Workspace, title: String, heightDp: Int): QuickPanel? {
            if (!anchor.isAttachedToWindow || !workspace.ready) return null
            dismiss()
            return QuickPanel(anchor, workspace, title, heightDp).takeIf { it.showing }
        }
        fun dismiss() { current.get()?.dismiss() }
        fun dismissFor(root: View) { current.get()?.takeIf { it.anchor.rootView === root.rootView }?.dismiss() }
    }
}
