package com.mekromn.bubble

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-window handoff.
 *
 * Floating -> fullscreen deliberately stays on the previously accepted system clip-reveal path.
 * Fullscreen -> floating uses a separate screenshot-driven morph: freeze the exact fullscreen
 * browser, stretch that one frame into the saved floating rectangle, then cross-fade it into the
 * already-positioned live floating card at the end of the same motion.
 */
internal object FullscreenHandoff {
    const val EXTRA_FROM_FLOATING = "bubble.transition.from.floating"
    private const val CAPTURE_TIMEOUT_MS = 180L
    private const val SHRINK_WATCHDOG_MS = 1400L
    private val main = Handler(Looper.getMainLooper())
    private var pendingFullscreenFrame: MorphFrame? = null
    private var shrinkOverlay: FullscreenShrinkOverlay? = null
    private var shrinkWatchdog: Runnable? = null

    /** Capture the fullscreen browser before BubbleService takes the Gecko surface. */
    fun armFullscreenToFloating(activity: Activity, root: View, ready: (Boolean) -> Unit) {
        cancelPendingFullscreenFrame()
        captureFullscreenFrame(activity, root, 0) { frame ->
            pendingFullscreenFrame = frame
            ready(frame != null)
        }
    }

    /** FloatingWindow uses this to suppress its ordinary independent entrance animation. */
    fun shouldSuppressDirectFloatingEntrance(): Boolean = pendingFullscreenFrame != null

    /**
     * Fullscreen -> floating only.
     *
     * The real floating card is attached at its exact final bounds but held at alpha 0. A frozen
     * fullscreen frame is placed above everything, BrowserActivity is moved behind the user's
     * previous app, and that one frame stretches into the floating rectangle. During the final
     * ~30% of the motion the frozen frame fades out while the live floating card fades in underneath.
     * At no point are two independently moving browser windows visible.
     */
    fun shrinkFullscreen(activity: Activity, root: View, target: WindowBox, done: () -> Unit) {
        reset(root)
        val source = pendingFullscreenFrame
        pendingFullscreenFrame = null
        val floating = BubbleService.active?.window?.takeIf { it.mode == FloatingMode.CHAT }
        val card = floating?.transitionView

        if (source == null || floating == null || card == null || !card.isAttachedToWindow || !card.isLaidOut) {
            source?.bitmap.safeRecycle()
            card?.let(::showFloatingCard)
            root.postOnAnimation { done() }
            return
        }

        card.animate().cancel()
        card.animate().withEndAction(null)
        card.alpha = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        card.translationX = 0f
        card.translationY = 0f

        val exactTarget = floating.box.takeIf { it.width > 0 && it.height > 0 } ?: target
        val overlay = FullscreenShrinkOverlay(
            activity.applicationContext,
            source,
            exactTarget,
            0f,
            Ui.dp(activity, 26f).toFloat()
        )
        shrinkOverlay?.detach()
        shrinkOverlay = overlay

        try {
            overlay.attach {
                scheduleShrinkWatchdog(overlay, card)
                // The frozen frame now covers the exact fullscreen browser, so the Activity can be
                // moved away without ever exposing its black backing surface.
                done()
                waitUntilSourceWindowHidden(activity, root, 0) {
                    if (shrinkOverlay !== overlay) return@waitUntilSourceWindowHidden
                    overlay.morphInto(card, durationMs = 340L, crossfadeStart = .70f) {
                        showFloatingCard(card)
                        clearShrinkWatchdog()
                        if (shrinkOverlay === overlay) shrinkOverlay = null
                        overlay.detach()
                    }
                }
            }
        } catch (_: RuntimeException) {
            clearShrinkWatchdog()
            if (shrinkOverlay === overlay) shrinkOverlay = null
            overlay.detach()
            showFloatingCard(card)
            done()
        }
    }

    /**
     * Keep floating -> fullscreen exactly on the accepted pre-matched-morph behavior. Android owns
     * the clip reveal from the whole floating card; Bubble does not insert a screenshot compositor.
     */
    fun launchFromFloating(context: Context, source: View, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_FROM_FLOATING, true)

        var launchSource = source
        var parent = source.parent
        while (parent is View && parent.isLaidOut && parent.width > 0 && parent.height > 0) {
            launchSource = parent
            parent = parent.parent
        }

        val options = if (launchSource.isLaidOut && launchSource.width > 0 && launchSource.height > 0) {
            ActivityOptions.makeClipRevealAnimation(
                launchSource,
                0,
                0,
                launchSource.width,
                launchSource.height
            ).toBundle()
        } else null
        context.startActivity(intent, options)
    }

    fun floatingTarget(context: Context, workspace: Workspace): WindowBox {
        val manager = context.getSystemService(WindowManager::class.java)
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            WindowBox(
                inset.left + dp(context, 4),
                inset.top + dp(context, 4),
                (metrics.bounds.width() - inset.left - inset.right - dp(context, 8)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - dp(context, 8)).coerceAtLeast(1)
            )
        } else {
            val p = Point()
            @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
            WindowBox(
                dp(context, 4),
                dp(context, 28),
                (p.x - dp(context, 8)).coerceAtLeast(1),
                (p.y - dp(context, 60)).coerceAtLeast(1)
            )
        }
        val width = (safe.width * WindowGeometry.fraction(workspace.windowWidth, .92f)).toInt()
            .coerceAtLeast(dp(context, 280)).coerceAtMost(dp(context, 560))
        val height = (safe.height * WindowGeometry.fraction(workspace.windowHeight, .72f)).toInt()
            .coerceAtLeast(dp(context, 260))
        return WindowGeometry.placed(safe, workspace.windowX, workspace.windowY, width, height)
    }

    fun reset(root: View) {
        root.animate().cancel()
        root.animate().withEndAction(null)
        root.scaleX = 1f
        root.scaleY = 1f
        root.translationX = 0f
        root.translationY = 0f
        root.alpha = 1f
        root.pivotX = root.width / 2f
        root.pivotY = root.height / 2f
    }

    /** Compatibility with BrowserActivity left by the previous matched-morph experiment. */
    fun isEnteringFullscreen(intent: Intent?): Boolean = false
    fun finishIntoFullscreen(activity: Activity, root: View) = Unit

    fun cancelAll() {
        cancelPendingFullscreenFrame()
        clearShrinkWatchdog()
        shrinkOverlay?.detach()
        shrinkOverlay = null
    }

    /**
     * Build a single fullscreen frame from native chrome plus Gecko's own compositor pixels. This is
     * faster and more reliable than waiting for a Window screenshot to rediscover a SurfaceView.
     */
    private fun captureFullscreenFrame(
        activity: Activity,
        root: View,
        attempt: Int,
        result: (MorphFrame?) -> Unit
    ) {
        if (!root.isLaidOut || root.width <= 0 || root.height <= 0 || activity.isFinishing) {
            if (attempt < 3) root.postOnAnimation { captureFullscreenFrame(activity, root, attempt + 1, result) }
            else result(null)
            return
        }

        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val box = WindowBox(location[0], location[1], root.width, root.height)
        val bitmap = try {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
        if (bitmap == null) {
            result(null)
            return
        }

        try {
            root.draw(Canvas(bitmap))
        } catch (_: Throwable) {
            bitmap.safeRecycle()
            result(null)
            return
        }

        val gecko = (activity as? BrowserActivity)?.geckoView
        if (gecko == null || !gecko.isAttachedToWindow || gecko.width <= 0 || gecko.height <= 0) {
            result(MorphFrame(bitmap, box))
            return
        }

        val finished = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            result(MorphFrame(bitmap, box))
        }
        timeout = Runnable { finish() }
        main.postDelayed(timeout, CAPTURE_TIMEOUT_MS)

        try {
            gecko.capturePixels().accept({ web ->
                if (finished.get()) {
                    web?.safeRecycle()
                    return@accept
                }
                if (web != null && !web.isRecycled) {
                    try {
                        val ownerLocation = IntArray(2)
                        val geckoLocation = IntArray(2)
                        root.getLocationOnScreen(ownerLocation)
                        gecko.getLocationOnScreen(geckoLocation)
                        val left = geckoLocation[0] - ownerLocation[0]
                        val top = geckoLocation[1] - ownerLocation[1]
                        val destination = Rect(left, top, left + gecko.width, top + gecko.height)
                        Canvas(bitmap).drawBitmap(web, null, destination, null)
                    } catch (_: Throwable) { }
                    web.safeRecycle()
                }
                finish()
            }, { _ -> finish() })
        } catch (_: RuntimeException) {
            finish()
        }
    }

    /** Start moving only after the original Activity is actually behind the user's previous task. */
    private fun waitUntilSourceWindowHidden(activity: Activity, root: View, attempt: Int, ready: () -> Unit) {
        val hidden = activity.isFinishing || !activity.hasWindowFocus() || !root.isAttachedToWindow ||
            root.windowVisibility != View.VISIBLE || !root.isShown
        if (hidden || attempt >= 40) {
            main.post(ready)
        } else {
            // 8 ms tracks a 120 Hz Pixel without imposing a 60 Hz polling cadence on the handoff.
            main.postDelayed({ waitUntilSourceWindowHidden(activity, root, attempt + 1, ready) }, 8L)
        }
    }

    private fun scheduleShrinkWatchdog(overlay: FullscreenShrinkOverlay, card: View) {
        clearShrinkWatchdog()
        val watchdog = Runnable {
            if (shrinkOverlay === overlay) {
                showFloatingCard(card)
                shrinkOverlay = null
                overlay.detach()
            }
        }
        shrinkWatchdog = watchdog
        main.postDelayed(watchdog, SHRINK_WATCHDOG_MS)
    }

    private fun clearShrinkWatchdog() {
        shrinkWatchdog?.let(main::removeCallbacks)
        shrinkWatchdog = null
    }

    private fun showFloatingCard(card: View) {
        if (!card.isAttachedToWindow) return
        card.animate().cancel()
        card.animate().withEndAction(null)
        card.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        card.translationX = 0f
        card.translationY = 0f
    }

    private fun cancelPendingFullscreenFrame() {
        pendingFullscreenFrame?.bitmap.safeRecycle()
        pendingFullscreenFrame = null
    }

    private fun Bitmap?.safeRecycle() {
        if (this != null && !isRecycled) recycle()
    }

    private fun dp(context: Context, value: Int) = Ui.dp(context, value.toFloat())
}