package com.mekromn.bubble

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Zero-work scheduler probe.
 *
 * This Activity deliberately does not host Gecko, animate, poll, schedule Choreographer callbacks,
 * request a display mode, or run a Handler loop. Its only purpose is to let a physical device answer
 * one question: does simply keeping a Bubble Activity in Android's TOP/RESUMED process state remove
 * the remaining floating-vs-fullscreen scroll gap?
 *
 * The window is a 1x1 transparent, non-touchable, non-modal floating application window. Bubble's
 * real floating browser remains the exact Build-158 single-ViewRoot TYPE_APPLICATION_OVERLAY path.
 * Therefore any scrolling change cannot be attributed to different Gecko/UI/rendering code.
 */
internal class FloatingPriorityAnchorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            width = 1
            height = 1
            dimAmount = 0f
            alpha = 1f
            title = "Bubble zero-work top-state anchor"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        lastImportance = info.importance
        lastRefreshRate = display?.refreshRate ?: 0f
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile var lastImportance: Int = Int.MIN_VALUE
            private set
        @Volatile var lastRefreshRate: Float = 0f
            private set
        private var current = WeakReference<FloatingPriorityAnchorActivity>(null)

        fun finishIfPresent() {
            current.get()?.runOnUiThread { current.get()?.finishAndRemoveTask() }
        }
    }
}
