package com.mekromn.bubble

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Cross-window motion keeps Gecko at a fixed render size and animates compositor transforms only.
 * The fullscreen Activity and TYPE_APPLICATION_OVERLAY never animate their layout bounds frame by
 * frame, so the page does not reflow and Android never exposes the old opaque black backing surface.
 */
internal object FullscreenHandoff {
    const val EXTRA_FROM_FLOATING = "bubble.transition.from.floating"
    private val main = Handler(Looper.getMainLooper())

    fun launchFromFloating(context: Context, source: View, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_FROM_FLOATING, true)
        // Walk from Gecko to the outer floating card so the system reveal originates from the
        // complete glass surface (header + page + utility bar), not just the webpage rectangle.
        var launchSource = source
        var parent = source.parent
        while (parent is View && parent.isLaidOut && parent.width > 0 && parent.height > 0) {
            launchSource = parent
            parent = parent.parent
        }
        // Clip reveal gives spatial continuity from the floating card to fullscreen instead of the
        // older generic scale-up. Android performs this in SurfaceFlinger, not Gecko.
        val options = if (launchSource.isLaidOut && launchSource.width > 0 && launchSource.height > 0)
            ActivityOptions.makeClipRevealAnimation(launchSource, 0, 0, launchSource.width, launchSource.height).toBundle()
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

    /**
     * Fullscreen -> floating is a coordinated handoff, not two unrelated fades.
     *
     * The overlay is already attached at its final WindowManager bounds. We cancel its ordinary
     * entrance, hold it fully transparent, spatially bias it toward the fullscreen center, send the
     * Activity behind the user's previous app/launcher, then let the card arrive with an emphasized
     * decelerating spring-settle. Header/content/footer are staggered a few milliseconds so the card
     * reads as one coherent surface rather than a rectangle suddenly appearing.
     *
     * Critically, the fullscreen Activity root is never shrunk. That was the source of the black
     * frame in the earlier recording because its opaque window backing became visible around it.
     */
    fun shrinkFullscreen(activity: Activity, root: View, target: WindowBox, done: () -> Unit) {
        reset(root)
        val floating = BubbleService.active?.window?.takeIf { it.mode == FloatingMode.CHAT }
        val card = floating?.transitionView
        if (card == null || !card.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            root.postOnAnimation { done() }
            return
        }

        card.animate().cancel(); card.animate().withEndAction(null)
        card.pivotX = card.width / 2f; card.pivotY = card.height / 2f
        val screenCx = root.width / 2f
        val screenCy = root.height / 2f
        val targetCx = target.x + target.width / 2f
        val targetCy = target.y + target.height / 2f
        val maxX = dp(activity, 88).toFloat()
        val maxY = dp(activity, 112).toFloat()
        card.translationX = (screenCx - targetCx).coerceIn(-maxX, maxX)
        card.translationY = (screenCy - targetCy).coerceIn(-maxY, maxY)
        card.scaleX = .82f; card.scaleY = .82f; card.alpha = 0f

        val column = (card as? ViewGroup)?.getChildAt(0) as? ViewGroup
        val top = column?.getChildAt(0)
        val body = column?.getChildAt(1)
        val bottom = column?.getChildAt((column.childCount - 1).coerceAtLeast(0))
        top?.apply { animate().cancel(); alpha = .35f; translationY = -dp(activity, 10).toFloat() }
        body?.apply { animate().cancel(); alpha = .58f; scaleX = .99f; scaleY = .99f }
        if (bottom !== body) bottom?.apply { animate().cancel(); alpha = .30f; translationY = dp(activity, 12).toFloat() }

        // First expose the user's underlying app, then animate the already-positioned overlay.
        root.postOnAnimation {
            done()
            main.postDelayed({
                if (!card.isAttachedToWindow) return@postDelayed
                // The ordinary direct-panel entrance may have begun a few milliseconds earlier;
                // claim the compositor here and replace it with the coordinated matched motion.
                card.animate().cancel(); card.animate().withEndAction(null)
                card.animate().withLayer().alpha(1f).translationX(0f).translationY(0f)
                    .scaleX(1.022f).scaleY(1.022f).setDuration(285).setInterpolator(Ui.ease)
                    .withEndAction {
                        if (card.isAttachedToWindow) card.animate().withLayer().scaleX(1f).scaleY(1f)
                            .setDuration(105).setInterpolator(Ui.ease).start()
                    }.start()

                top?.animate()?.withLayer()?.alpha(1f)?.translationY(0f)?.setStartDelay(28)?.setDuration(205)?.setInterpolator(Ui.ease)?.start()
                body?.animate()?.withLayer()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setStartDelay(42)?.setDuration(235)?.setInterpolator(Ui.ease)?.start()
                if (bottom !== body) bottom?.animate()?.withLayer()?.alpha(1f)?.translationY(0f)?.setStartDelay(64)?.setDuration(215)?.setInterpolator(Ui.ease)?.start()
            }, 22L)
        }
    }

    fun reset(root: View) {
        root.animate().cancel(); root.animate().withEndAction(null)
        root.scaleX = 1f; root.scaleY = 1f; root.translationX = 0f; root.translationY = 0f; root.alpha = 1f
        root.pivotX = root.width / 2f; root.pivotY = root.height / 2f
    }

    private fun dp(context: Context, value: Int) = Ui.dp(context, value.toFloat())
}
