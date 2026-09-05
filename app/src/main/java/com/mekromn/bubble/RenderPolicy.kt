package com.mekromn.bubble

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/** Real supported display modes only. No fake FPS, busy rendering loop or permanent bitmap layer. */
internal object RenderPolicy {
    fun vote(context: Context, view: View, params: WindowManager.LayoutParams? = null) {
        val display = view.display ?: context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode
        val mode = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: current
        params?.let { it.preferredDisplayModeId = mode.modeId; it.preferredRefreshRate = mode.refreshRate }
        if (Build.VERSION.SDK_INT >= 35) voteTree(view, mode.refreshRate)
    }
    private fun voteTree(view: View, rate: Float) {
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate)
        if (view is ViewGroup) for (i in 0 until view.childCount) voteTree(view.getChildAt(i), rate)
    }
}
