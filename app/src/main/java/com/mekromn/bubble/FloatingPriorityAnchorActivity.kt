package com.mekromn.bubble

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Zero-work Activity used only to keep Bubble in Android's TOP/RESUMED scheduling class while the
 * real Build-158 floating browser remains an unchanged TYPE_APPLICATION_OVERLAY window.
 *
 * This Activity owns no GeckoSession, renderer, frame callback, timer, display-mode vote or input.
 * Its application window is 1x1, transparent, non-touchable and isolated in its own task affinity.
 */
internal class FloatingPriorityAnchorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (!wanted) {
            finishAndRemoveTask()
            return
        }

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
            title = "Bubble floating priority anchor"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        if (!wanted && !isFinishing) finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile private var wanted = false
        private var current = WeakReference<FloatingPriorityAnchorActivity>(null)

        fun setWanted(value: Boolean) {
            wanted = value
            if (!value) finishIfPresent()
        }

        fun finishIfPresent() {
            val activity = current.get() ?: return
            activity.runOnUiThread {
                if (current.get() === activity && !activity.isFinishing) activity.finishAndRemoveTask()
            }
        }
    }
}
