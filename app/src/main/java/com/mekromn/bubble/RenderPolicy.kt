package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * One aggressive high-refresh policy for fullscreen and floating rendering.
 *
 * Window/display-mode votes alone are not enough for Bubble's raw floating renderer because the
 * webpage lives in its own SurfaceView/Surface. On Android 11+ every real SurfaceView producer gets
 * an explicit Surface.setFrameRate() contract too. Android 16's FRAME_RATE_COMPATIBILITY_AT_LEAST is
 * designed for UI/scrolling/fling, so use it when available.
 *
 * Android 15+ touch boost is explicitly enabled and the power-savings-balanced frame-rate policy is
 * disabled for Bubble windows. That intentionally spends more display power to favor smoothness.
 * Surface frame-rate hints are applied once per Surface lifetime, never every frame.
 */
internal object RenderPolicy {
    private data class SurfaceVote(var rate: Float, var voted: Boolean = false)
    private val surfaceVotes = WeakHashMap<SurfaceView, SurfaceVote>()

    fun vote(context: Context, view: View, params: WindowManager.LayoutParams? = null): Float {
        val display = view.display
            ?: context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 0f
        val current = display.mode
        val mode = display.supportedModes
            .asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: current

        params?.let {
            // Exact same-resolution maximum mode request for both fullscreen and overlay windows.
            it.preferredDisplayModeId = mode.modeId
            it.preferredRefreshRate = mode.refreshRate
            if (Build.VERSION.SDK_INT >= 35) {
                it.setFrameRateBoostOnTouchEnabled(true)
                it.setFrameRatePowerSavingsBalanced(false)
            }
        }
        voteTree(view, mode.refreshRate)
        return mode.refreshRate
    }

    /** Apply the same producer-level rate contract to an already-selected maximum rate. */
    fun voteTree(view: View, rate: Float) {
        if (rate <= 0f) return
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate)
        if (view is SurfaceView) voteSurfaceView(view, rate)
        if (view is ViewGroup) {
            // View.setRequestedFrameRate does NOT propagate from a ViewGroup to its children.
            for (i in 0 until view.childCount) voteTree(view.getChildAt(i), rate)
        }
    }

    @SuppressLint("NewApi")
    private fun voteSurfaceView(view: SurfaceView, rate: Float) {
        if (Build.VERSION.SDK_INT < 30) return

        val state: SurfaceVote
        synchronized(surfaceVotes) {
            state = surfaceVotes.getOrPut(view) {
                val created = SurfaceVote(rate)
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        created.voted = false
                        applySurfaceRate(holder.surface, created)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // Resizing can call this repeatedly. Do not re-vote an unchanged live Surface.
                        if (!created.voted) applySurfaceRate(holder.surface, created)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        created.voted = false
                    }
                })
                created
            }
            if (state.rate != rate) {
                state.rate = rate
                state.voted = false
            }
        }
        if (!state.voted) applySurfaceRate(view.holder.surface, state)
    }

    @SuppressLint("NewApi")
    private fun applySurfaceRate(surface: Surface, state: SurfaceVote) {
        if (Build.VERSION.SDK_INT < 30 || !surface.isValid || state.rate <= 0f || state.voted) return
        runCatching {
            val compatibility = if (Build.VERSION.SDK_INT >= 36) {
                // Android 16 explicitly recommends AT_LEAST for UI, scrolling, fling and animation.
                Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST
            } else {
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            }
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    state.rate,
                    compatibility,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(state.rate, compatibility)
            }
            state.voted = true
        }
    }
}
