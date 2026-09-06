package com.mekromn.bubble

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Cross-window motion deliberately animates transforms, not Gecko layout, so the compositor is
 * not reflowed on every animation frame. The live GeckoSession is handed over only at the end. */
internal object FullscreenHandoff {
    const val EXTRA_FROM_FLOATING = "bubble.transition.from.floating"

    fun launchFromFloating(context: Context, source: View, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_FROM_FLOATING, true)
        val options = if (source.isLaidOut && source.width > 0 && source.height > 0)
            ActivityOptions.makeScaleUpAnimation(source, 0, 0, source.width, source.height).toBundle()
        else null
        context.startActivity(intent, options)
    }

    fun floatingTarget(context: Context, workspace: Workspace): WindowBox {
        val manager = context.getSystemService(WindowManager::class.java)
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            WindowBox(inset.left + dp(context, 4), inset.top + dp(context, 4),
                (metrics.bounds.width() - inset.left - inset.right - dp(context, 8)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - dp(context, 8)).coerceAtLeast(1))
        } else {
            val p = Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
            WindowBox(dp(context, 4), dp(context, 28), (p.x - dp(context, 8)).coerceAtLeast(1), (p.y - dp(context, 60)).coerceAtLeast(1))
        }
        val width = (safe.width * WindowGeometry.fraction(workspace.windowWidth, .92f)).toInt()
            .coerceAtLeast(dp(context, 280)).coerceAtMost(dp(context, 560))
        val height = (safe.height * WindowGeometry.fraction(workspace.windowHeight, .72f)).toInt().coerceAtLeast(dp(context, 260))
        return WindowGeometry.placed(safe, workspace.windowX, workspace.windowY, width, height)
    }

    fun shrinkFullscreen(activity: Activity, root: View, target: WindowBox, done: () -> Unit) {
        root.animate().cancel()
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || !root.isLaidOut || root.width <= 0 || root.height <= 0) {
            done(); return
        }
        val location = IntArray(2); root.getLocationOnScreen(location)
        root.pivotX = 0f; root.pivotY = 0f
        val sx = target.width.toFloat() / root.width.coerceAtLeast(1)
        val sy = target.height.toFloat() / root.height.coerceAtLeast(1)
        val tx = (target.x - location[0]).toFloat()
        val ty = (target.y - location[1]).toFloat()
        root.animate().scaleX(sx).scaleY(sy).translationX(tx).translationY(ty)
            .setDuration(235).setInterpolator(Ui.ease).withEndAction(done).start()
    }

    fun reset(root: View) {
        root.animate().cancel(); root.animate().withEndAction(null)
        root.scaleX = 1f; root.scaleY = 1f; root.translationX = 0f; root.translationY = 0f; root.alpha = 1f
        root.pivotX = root.width / 2f; root.pivotY = root.height / 2f
    }

    private fun dp(context: Context, value: Int) = Ui.dp(context, value.toFloat())
}
