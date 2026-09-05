package com.mekromn.bubble

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Native black-glass sheet: explicit Back priority only while visible; search IME first. */
internal class TabTray(c: Context, select: (String) -> Unit, close: (String) -> Unit,
    newChat: () -> Unit, private val dismiss: () -> Unit) : LinearLayout(c) {
    private val conversations = ConversationList(c, select, close)
    private val count = Ui.text(c, "", 12f, Ui.MUTED)
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null
    init {
        orientation = VERTICAL; background = Ui.shape(c, Ui.SURFACE, 0f); isClickable = true; isFocusable = true
        setPadding(d(12), d(8), d(12), d(12))
        val header = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(c).apply { orientation = VERTICAL; setPadding(d(8), 0, 0, 0) }
        labels.addView(Ui.text(c, "Your workspace", 23f, Ui.TEXT, true))
        count.setPadding(0,d(4),0,0); labels.addView(count); header.addView(labels, LayoutParams(0,-2,1f))
        header.addView(GlyphView(c,"close","Close workspace").apply { setOnClickListener { dismiss() } }, LayoutParams(d(48),d(48)))
        addView(header, LayoutParams(-1,d(60)))
        val search = EditText(c).apply {
            hint="Find a conversation"; contentDescription="Find a conversation"; setSingleLine(true)
            textSize=14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            setPadding(d(16),0,d(16),0); background=Ui.shape(c,Ui.BG,18f,Ui.LINE)
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { conversations.search(s.toString()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LayoutParams(-1,d(48)).apply { setMargins(d(8),d(10),d(8),d(8)) })
        addView(conversations, LayoutParams(-1,0,1f))
        addView(Ui.text(c,"＋  New ChatGPT chat",15f,Ui.TEXT,true).apply {
            gravity=Gravity.CENTER; background=Ui.ripple(c,Ui.SURFACE_HIGH,24f); setOnClickListener { newChat() }
        }, LayoutParams(-1,d(50)).apply { setMargins(d(8),d(8),d(8),0) })
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncBackHandler() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView,visibility); if (isAttachedToWindow) syncBackHandler()
    }
    override fun onDetachedFromWindow() { releaseBackHandler(); super.onDetachedFromWindow() }
    private fun syncBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!isShown) { releaseBackHandler(); return }
        if (backCallback != null) return
        val dispatcher=findOnBackInvokedDispatcher() ?: return
        val callback=android.window.OnBackInvokedCallback {
            if (ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken,0)
            } else dismiss()
        }
        backDispatcher=dispatcher; backCallback=callback
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
    }
    private fun releaseBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        backCallback=null; backDispatcher=null
    }
    fun refresh(workspace: Workspace) {
        val value="${workspace.tabs.size} tabs · all kept live"
        if (count.text != value) count.text=value
        conversations.refresh(workspace)
    }
    private fun d(n: Int)=Ui.dp(context,n.toFloat())
}
