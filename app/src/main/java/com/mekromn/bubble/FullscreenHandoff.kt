package com.mekromn.bubble

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.GeckoView

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
    private const val GECKO_CAPTURE_TIMEOUT_MS = 280L
    private const val SURFACE_COPY_RETRIES = 3
    private const val SHRINK_WATCHDOG_MS = 1400L
    private val main = Handler(Looper.getMainLooper())
    private val capturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
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

        if (source == null || floating == null || card == null) {
            source?.bitmap.safeRecycle()
            card?.let(::showFloatingCard)
            root.postOnAnimation { done() }
            return
        }

        /** WindowManager acknowledgement can precede the overlay's first layout by a frame. */
        fun begin(attempt: Int) {
            if (activity.isFinishing) {
                source.bitmap.safeRecycle()
                showFloatingCard(card)
                done()
                return
            }
            if (!card.isAttachedToWindow || !card.isLaidOut || card.width <= 0 || card.height <= 0) {
                if (attempt < 24) {
                    main.postDelayed({ begin(attempt + 1) }, 8L)
                } else {
                    source.bitmap.safeRecycle()
                    showFloatingCard(card)
                    done()
                }
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
                    // The frozen frame now covers the exact fullscreen browser, so the Activity can
                    // be moved away without ever exposing its black backing surface.
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

        begin(0)
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

    /** Compatibility with BrowserActivity left by the rejected matched-morph experiment. */
    fun isEnteringFullscreen(intent: Intent?): Boolean = false
    fun finishIntoFullscreen(activity: Activity, root: View) = Unit

    fun cancelAll() {
        cancelPendingFullscreenFrame()
        clearShrinkWatchdog()
        shrinkOverlay?.detach()
        shrinkOverlay = null
    }

    /**
     * Capture the exact fullscreen browser while its SurfaceView still belongs to BrowserActivity.
     * Window PixelCopy captures native chrome. Gecko's SurfaceView is a separate SurfaceControl, so
     * it is copied independently with PixelCopy and composited at its exact screen rectangle. Gecko
     * capturePixels remains a bounded fallback for renderer/backend variants with no discoverable
     * SurfaceView. BubbleService is not started until this finishes, so nothing can change owners
     * underneath the capture.
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

        fun finishBase(base: Bitmap) {
            val gecko = (activity as? BrowserActivity)?.geckoView
            compositeGeckoPixels(gecko, root, base) {
                result(MorphFrame(base, box))
            }
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (code == PixelCopy.SUCCESS) {
                        finishBase(bitmap)
                    } else {
                        bitmap.safeRecycle()
                        if (attempt < 2) {
                            main.postDelayed(
                                { captureFullscreenFrame(activity, root, attempt + 1, result) },
                                24L
                            )
                        } else {
                            val fallback = drawFallback(root)
                            if (fallback == null) result(null) else finishBase(fallback)
                        }
                    }
                }, main)
                return
            } catch (_: RuntimeException) { }
        }

        bitmap.safeRecycle()
        val fallback = drawFallback(root)
        if (fallback == null) result(null) else finishBase(fallback)
    }

    /** Prefer direct PixelCopy from Gecko's actual SurfaceView; fall back to Gecko compositor readback. */
    private fun compositeGeckoPixels(
        gecko: GeckoView?,
        owner: View,
        base: Bitmap,
        done: (Boolean) -> Unit
    ) {
        if (gecko == null || !gecko.isAttachedToWindow || gecko.width <= 0 || gecko.height <= 0) {
            done(false)
            return
        }

        val surface = findSurfaceView(gecko)
        if (surface != null && Build.VERSION.SDK_INT >= 24) {
            pixelCopySurface(surface, owner, base, 0) { copied ->
                if (copied) done(true)
                else captureGeckoCompositor(gecko, owner, base, done)
            }
        } else {
            captureGeckoCompositor(gecko, owner, base, done)
        }
    }

    private fun pixelCopySurface(
        surface: SurfaceView,
        owner: View,
        base: Bitmap,
        attempt: Int,
        done: (Boolean) -> Unit
    ) {
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) {
            done(false)
            return
        }
        val web = try {
            Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
        if (web == null) {
            done(false)
            return
        }

        try {
            PixelCopy.request(surface, web, { code ->
                if (code == PixelCopy.SUCCESS && !web.isRecycled) {
                    try {
                        compositeIntoOwner(surface, owner, base, web)
                        web.safeRecycle()
                        done(true)
                    } catch (_: Throwable) {
                        web.safeRecycle()
                        done(false)
                    }
                } else {
                    web.safeRecycle()
                    if (attempt < SURFACE_COPY_RETRIES && surface.isAttachedToWindow) {
                        main.postDelayed(
                            { pixelCopySurface(surface, owner, base, attempt + 1, done) },
                            16L * (attempt + 1)
                        )
                    } else {
                        done(false)
                    }
                }
            }, main)
        } catch (_: RuntimeException) {
            web.safeRecycle()
            if (attempt < SURFACE_COPY_RETRIES && surface.isAttachedToWindow) {
                main.postDelayed(
                    { pixelCopySurface(surface, owner, base, attempt + 1, done) },
                    16L * (attempt + 1)
                )
            } else {
                done(false)
            }
        }
    }

    /** Gecko compositor fallback for non-SurfaceView backends or PixelCopy source-no-data races. */
    private fun captureGeckoCompositor(
        gecko: GeckoView,
        owner: View,
        base: Bitmap,
        done: (Boolean) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val deadline = SystemClock.uptimeMillis() + GECKO_CAPTURE_TIMEOUT_MS
        lateinit var timeout: Runnable
        lateinit var attemptCapture: (Int) -> Unit

        fun finish(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            if (Looper.myLooper() === Looper.getMainLooper()) done(success) else main.post { done(success) }
        }

        fun retryOrFinish(index: Int) {
            if (finished.get()) return
            val delay = 20L * (index + 1)
            if (
                index < 3 && gecko.isAttachedToWindow &&
                SystemClock.uptimeMillis() + delay < deadline
            ) {
                main.postDelayed({ attemptCapture(index + 1) }, delay)
            } else {
                finish(false)
            }
        }

        timeout = Runnable { finish(false) }
        main.postDelayed(timeout, GECKO_CAPTURE_TIMEOUT_MS)

        attemptCapture = { index ->
            if (!finished.get()) {
                if (SystemClock.uptimeMillis() >= deadline || !gecko.isAttachedToWindow) {
                    finish(false)
                } else {
                    try {
                        gecko.capturePixels().accept({ web ->
                            if (finished.get()) {
                                web?.safeRecycle()
                            } else if (web != null && !web.isRecycled) {
                                val stale = kotlin.math.abs(web.width - gecko.width) > 8 ||
                                    kotlin.math.abs(web.height - gecko.height) > 8
                                val delay = 20L * (index + 1)
                                if (
                                    stale && index < 3 && gecko.isAttachedToWindow &&
                                    SystemClock.uptimeMillis() + delay < deadline
                                ) {
                                    web.safeRecycle()
                                    main.postDelayed({ attemptCapture(index + 1) }, delay)
                                } else {
                                    try {
                                        compositeIntoOwner(gecko, owner, base, web)
                                        web.safeRecycle()
                                        finish(true)
                                    } catch (_: Throwable) {
                                        web.safeRecycle()
                                        retryOrFinish(index)
                                    }
                                }
                            } else {
                                retryOrFinish(index)
                            }
                        }, { _ -> retryOrFinish(index) })
                    } catch (_: RuntimeException) {
                        retryOrFinish(index)
                    }
                }
            }
        }

        attemptCapture(0)
    }

    private fun compositeIntoOwner(sourceView: View, owner: View, base: Bitmap, pixels: Bitmap) {
        val ownerLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        owner.getLocationOnScreen(ownerLocation)
        sourceView.getLocationOnScreen(sourceLocation)
        val left = sourceLocation[0] - ownerLocation[0]
        val top = sourceLocation[1] - ownerLocation[1]
        val destination = Rect(left, top, left + sourceView.width, top + sourceView.height)
        Canvas(base).drawBitmap(pixels, null, destination, capturePaint)
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView && view.isAttachedToWindow && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun drawFallback(root: View): Bitmap? {
        val bitmap = try {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        return try {
            root.draw(Canvas(bitmap))
            bitmap
        } catch (_: Throwable) {
            bitmap.safeRecycle()
            null
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
