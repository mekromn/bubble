package com.mekromn.bubble

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Real system compositor blur for the floating overlay on Android 12+.
 *
 * TYPE_APPLICATION_OVERLAY does not expose an android.view.Window, so the public platform API
 * available to this overlay is LayoutParams blur-behind. It is a genuine cross-window blur (not a
 * screenshot or RenderEffect imitation). The translucent panel tint remains useful when the GPU,
 * battery saver, or system policy temporarily disables cross-window blur.
 */
internal object OverlayGlass {
    fun available(manager: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled

    fun apply(context: Context, manager: WindowManager, params: WindowManager.LayoutParams, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val useBlur = enabled && manager.isCrossWindowBlurEnabled
        if (useBlur) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            // Android's guidance calls ~20 px a good blur-behind radius. Keep it bounded so the
            // always-on-top workspace remains cheap enough for smooth motion.
            params.setBlurBehindRadius(Ui.dp(context, 6f).coerceIn(12, 28))
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.setBlurBehindRadius(0)
        }
    }
}
