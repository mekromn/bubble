package com.mekromn.bubble

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Experimental scheduler probe only.
 *
 * Fullscreen Bubble owns a resumed Activity while floating Bubble normally owns only a foreground
 * service plus TYPE_APPLICATION_OVERLAY windows. Android documents those as different process
 * importance classes. This 1x1, fully transparent, non-touchable application window lets us test
 * that single difference without changing Gecko, the floating renderer, page pixels, chrome, or
 * touch delivery. FLAG_NOT_TOUCH_MODAL/NOT_TOUCHABLE and alpha=0 keep the probe out of the input
 * path; Android's documented pass-through rules permit a fully transparent window.
 *
 * noHistory in the manifest makes the probe self-retire when another Activity takes over. It is not
 * a production architecture: if the experiment proves the hypothesis we will replace it with a
 * lifecycle-owned application-window floating host rather than keeping an invisible anchor.
 */
class FloatingTopAppAnchorActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        window.setDimAmount(0f)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            width = 1
            height = 1
            x = 0
            y = 0
            alpha = 0f
            dimAmount = 0f
            if (android.os.Build.VERSION.SDK_INT >= 36) setCanPlayMoveAnimation(false)
            title = "Bubble top-app scheduling probe"
        }
        setContentView(View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    override fun onResume() {
        super.onResume()
        active = WeakReference(this)
        captureImportance()
        window.decorView.post { captureImportance() }
    }

    override fun onPause() {
        captureImportance()
        super.onPause()
    }

    override fun onDestroy() {
        if (active.get() === this) active.clear()
        super.onDestroy()
    }

    private fun captureImportance() {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        lastImportance = info.importance
    }

    companion object {
        private var active = WeakReference<FloatingTopAppAnchorActivity>(null)
        @Volatile var lastImportance: Int = Int.MAX_VALUE
            private set

        fun ensure(context: Context) {
            if (active.get()?.isFinishing == false) return
            val intent = Intent(context, FloatingTopAppAnchorActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            runCatching { context.startActivity(intent) }
        }
    }
}
