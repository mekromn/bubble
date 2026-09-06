package com.mekromn.bubble

import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference

/** The actual OS file picker must be above the floating browser, not hidden behind it. */
internal object FileUi {
    private var token: String? = null
    private var hidden = WeakReference<View>(null)
    val busy: Boolean get() = token != null
    fun begin(id: String): Boolean {
        if (token != null) return token == id
        token = id
        QuickPanel.dismiss()
        val view = BubbleService.active?.window?.geckoView?.rootView
        if (view != null && view.isAttachedToWindow) {
            hidden = WeakReference(view)
            view.visibility = View.INVISIBLE
            val layout = view.layoutParams as? WindowManager.LayoutParams
            if (layout != null) {
                layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                runCatching { view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, layout) }
            }
        }
        return true
    }
    fun end(id: String) {
        if (token != id) return
        token = null
        hidden.get()?.takeIf { it.isAttachedToWindow }?.let { view ->
            val layout = view.layoutParams as? WindowManager.LayoutParams
            if (layout != null) {
                layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                runCatching { view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, layout) }
            }
            view.visibility = View.VISIBLE
        }
        hidden.clear()
        Workspace.peek()?.applyPolicy()
    }
}
