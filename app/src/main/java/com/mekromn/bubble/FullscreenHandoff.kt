package com.mekromn.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.GeckoView

/**
 * Matched Activity <-> overlay handoff.
 *
 * The transition owns one visible object from end to end. Source/destination chrome is frozen into
 * bitmaps, and Gecko's compositor pixels are captured separately and composited into those bitmaps
 * so SurfaceView/TextureView ownership changes can never create black holes in the held frame.
 */
internal object FullscreenHandoff {
    const val EXTRA_FROM_FLOATING = "bubble.transition.from.floating"
    private const val GECKO_CAPTURE_TIMEOUT_MS = 240L
    private const val FULLSCREEN_CAPTURE_TIMEOUT_MS = 520L
    private const val SHRINK_WATCHDOG_MS = 1800L
    private const val EXPANSION_WATCHDOG_MS = 1800L
    private val main = Handler(Looper.getMainLooper())
    private val capturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private var pendingFullscreenFrame: MorphFrame? = null
    private var morphOverlay: WindowMorphOverlay? = null
    private var expandingIntoFullscreen = false

    /** Capture the exact fullscreen pixels before BubbleService steals the GeckoSession surface. */
    fun armFullscreenToFloating(activity: Activity, root: View, ready: (Boolean) -> Unit) {
        cancelPendingFullscreenFrame()
        captureActivityFrame(activity, root, 0) { frame ->
            pendingFullscreenFrame = frame
            ready(frame != null)
        }
    }

    fun shouldSuppressDirectFloatingEntrance(): Boolean = pendingFullscreenFrame != null

    /** Fullscreen -> floating: the held fullscreen frame itself contracts into the saved card. */
    fun shrinkFullscreen(activity: Activity, root: View, target: WindowBox, done: () -> Unit) {
        reset(root)
        val source = pendingFullscreenFrame
        pendingFullscreenFrame = null
        val floating = BubbleService.active?.window?.takeIf { it.mode == FloatingMode.CHAT }
        if (source == null || floating == null) {
            source?.bitmap.safeRecycle()
            root.postOnAnimation { done() }
            return
        }

        holdFloating(floating)
        root.postOnAnimation {
            if (activity.isFinishing) {
                source.bitmap.safeRecycle(); showFloating(floating); done(); return@postOnAnimation
            }
            // The floating Gecko surface has only just received the existing session. capturePixels
            // is deliberately bounded: a compositor capture is a fidelity enhancement, never a
            // reason to freeze the user in a transition if a backend handoff stalls.
            captureFloatingFrame(floating) { destination ->
                if (activity.isFinishing) {
                    destination?.bitmap.safeRecycle(); source.bitmap.safeRecycle()
                    showFloating(floating); done(); return@captureFloatingFrame
                }
                val exactTarget = floating.box.takeIf { it.width > 0 && it.height > 0 } ?: target
                val overlay = WindowMorphOverlay(
                    activity.applicationContext,
                    source,
                    exactTarget,
                    0f,
                    Ui.dp(activity, 26f).toFloat()
                )
                morphOverlay?.detach(); morphOverlay = overlay
                try {
                    overlay.attach {
                        destination?.let(overlay::setDestination)
                        scheduleShrinkWatchdog(overlay, floating)
                        // The captured fullscreen frame is now the only visible app surface. Ask
                        // Android to background BrowserActivity, but keep the held frame full-screen
                        // until the real source window is no longer visible.
                        done()
                        waitUntilSourceWindowHidden(activity, root, 0) {
                            if (morphOverlay === overlay) {
                                overlay.morph(365L) {
                                    showFloating(floating)
                                    if (morphOverlay === overlay) morphOverlay = null
                                    overlay.detach()
                                }
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    destination?.bitmap.safeRecycle()
                    if (morphOverlay === overlay) morphOverlay = null
                    overlay.detach()
                    showFloating(floating)
                    done()
                }
            }
        }
    }

    /** Floating -> fullscreen: grow the exact card to the display, then hand it to BrowserActivity. */
    fun expandFloatingToFullscreen(context: Context, floating: FloatingWindow, intent: Intent) {
        if (expandingIntoFullscreen || morphOverlay != null) return
        expandingIntoFullscreen = true
        captureFloatingFrame(floating) { source ->
            if (source == null) {
                expandingIntoFullscreen = false
                launchFullscreenDirect(context, intent)
                return@captureFloatingFrame
            }
            val destination = WindowMorphOverlay.displayBox(context)
            val overlay = WindowMorphOverlay(
                context,
                source,
                destination,
                Ui.dp(context, 26f).toFloat(),
                0f
            )
            morphOverlay = overlay
            try {
                overlay.attach {
                    holdFloating(floating)
                    scheduleExpansionWatchdog(overlay, floating)
                    overlay.morph(380L) {
                        // The card is now physically full-screen and opaque. Start BrowserActivity
                        // behind that held frame with Android's unrelated task animation suppressed;
                        // the user continues to see only the one shared morph object.
                        val launch = Intent(intent).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                            putExtra(EXTRA_FROM_FLOATING, true)
                        }
                        try {
                            context.applicationContext.startActivity(launch)
                        } catch (_: RuntimeException) {
                            expandingIntoFullscreen = false
                            showFloating(floating)
                            if (morphOverlay === overlay) morphOverlay = null
                            overlay.detach()
                        }
                    }
                }
            } catch (_: RuntimeException) {
                if (morphOverlay === overlay) morphOverlay = null
                expandingIntoFullscreen = false
                overlay.detach()
                showFloating(floating)
                launchFullscreenDirect(context, intent)
            }
        }
    }

    /** BrowserActivity is ready behind the full-size held card; dissolve only at identical bounds. */
    fun finishIntoFullscreen(activity: Activity, root: View) {
        if (!expandingIntoFullscreen) return
        val overlay = morphOverlay ?: run { expandingIntoFullscreen = false; return }
        // Do not gate this on window focus. A non-focusable TYPE_APPLICATION_OVERLAY can cover the
        // destination while it is already laid out and drawable, and some Android builds defer the
        // focus transition until the overlay is removed. Waiting for focus therefore creates a
        // circular handoff. Instead wait for attachment/layout/visibility and capture behind the
        // held full-screen frame. The capture itself also has a hard deadline: if SurfaceView or
        // PixelCopy stalls, the overlay is removed at identical fullscreen geometry rather than ever
        // becoming a frozen screen.
        waitUntilDestinationWindowReady(activity, root, 0) {
            if (morphOverlay === overlay && expandingIntoFullscreen) {
                main.postDelayed({
                    if (morphOverlay === overlay && expandingIntoFullscreen) {
                        val delivered = AtomicBoolean(false)
                        val timeout = Runnable {
                            if (delivered.compareAndSet(false, true) && morphOverlay === overlay && expandingIntoFullscreen) {
                                morphOverlay = null
                                expandingIntoFullscreen = false
                                overlay.detach()
                            }
                        }
                        main.postDelayed(timeout, FULLSCREEN_CAPTURE_TIMEOUT_MS)
                        captureActivityFrame(activity, root, 0) { destination ->
                            if (delivered.compareAndSet(false, true)) {
                                main.removeCallbacks(timeout)
                                if (morphOverlay === overlay && expandingIntoFullscreen) {
                                    overlay.finishWith(destination, 115L) {
                                        if (morphOverlay === overlay) morphOverlay = null
                                        expandingIntoFullscreen = false
                                        overlay.detach()
                                    }
                                } else destination?.bitmap.safeRecycle()
                            } else destination?.bitmap.safeRecycle()
                        }
                    }
                }, 32L)
            }
        }
    }

    fun isEnteringFullscreen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FLOATING, false) == true && expandingIntoFullscreen

    /** BubbleService funnels BrowserActivity launches here so the entire card is the shared object. */
    fun launchFromFloating(context: Context, source: View, intent: Intent) {
        val floating = BubbleService.active?.window?.takeIf { it.mode == FloatingMode.CHAT }
        if (floating != null) expandFloatingToFullscreen(context, floating, intent)
        else launchFullscreenDirect(context, intent)
    }

    fun floatingTarget(context: Context, workspace: Workspace): WindowBox {
        val manager = context.getSystemService(android.view.WindowManager::class.java)
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            WindowBox(inset.left + Ui.dp(context, 4f), inset.top + Ui.dp(context, 4f),
                (metrics.bounds.width() - inset.left - inset.right - Ui.dp(context, 8f)).coerceAtLeast(1),
                (metrics.bounds.height() - inset.top - inset.bottom - Ui.dp(context, 8f)).coerceAtLeast(1))
        } else {
            val p = android.graphics.Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
            WindowBox(Ui.dp(context, 4f), Ui.dp(context, 28f), (p.x - Ui.dp(context, 8f)).coerceAtLeast(1), (p.y - Ui.dp(context, 60f)).coerceAtLeast(1))
        }
        val width = (safe.width * WindowGeometry.fraction(workspace.windowWidth, .92f)).toInt()
            .coerceAtLeast(Ui.dp(context, 280f)).coerceAtMost(Ui.dp(context, 560f))
        val height = (safe.height * WindowGeometry.fraction(workspace.windowHeight, .72f)).toInt().coerceAtLeast(Ui.dp(context, 260f))
        return WindowGeometry.placed(safe, workspace.windowX, workspace.windowY, width, height)
    }

    fun reset(root: View) {
        root.animate().cancel(); root.animate().withEndAction(null)
        root.scaleX = 1f; root.scaleY = 1f; root.translationX = 0f; root.translationY = 0f; root.alpha = 1f
        root.pivotX = root.width / 2f; root.pivotY = root.height / 2f
    }

    fun cancelAll() {
        cancelPendingFullscreenFrame()
        morphOverlay?.detach(); morphOverlay = null
        expandingIntoFullscreen = false
    }

    private fun holdFloating(floating: FloatingWindow) {
        val card = floating.transitionView
        card.animate().cancel(); card.animate().withEndAction(null)
        card.scaleX = 1f; card.scaleY = 1f; card.translationX = 0f; card.translationY = 0f
        card.alpha = 0f
    }

    private fun showFloating(floating: FloatingWindow) {
        val card = floating.transitionView
        if (!card.isAttachedToWindow) return
        card.animate().cancel(); card.animate().withEndAction(null)
        card.scaleX = 1f; card.scaleY = 1f; card.translationX = 0f; card.translationY = 0f; card.alpha = 1f
    }

    /** Capture native floating chrome, then replace the Gecko rectangle with compositor pixels. */
    private fun captureFloatingFrame(floating: FloatingWindow, result: (MorphFrame?) -> Unit) {
        val card = floating.transitionView
        if (!card.isLaidOut || !card.isAttachedToWindow || card.width <= 0 || card.height <= 0) {
            result(null); return
        }
        val oldAlpha = card.alpha
        val oldSx = card.scaleX; val oldSy = card.scaleY
        val oldTx = card.translationX; val oldTy = card.translationY
        card.alpha = 1f; card.scaleX = 1f; card.scaleY = 1f; card.translationX = 0f; card.translationY = 0f
        val bitmap = try { Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        if (bitmap == null) {
            card.alpha = oldAlpha; card.scaleX = oldSx; card.scaleY = oldSy; card.translationX = oldTx; card.translationY = oldTy
            result(null); return
        }
        try {
            card.draw(Canvas(bitmap))
        } catch (_: Throwable) {
            bitmap.safeRecycle()
            card.alpha = oldAlpha; card.scaleX = oldSx; card.scaleY = oldSy; card.translationX = oldTx; card.translationY = oldTy
            result(null); return
        }
        card.alpha = oldAlpha; card.scaleX = oldSx; card.scaleY = oldSy; card.translationX = oldTx; card.translationY = oldTy
        val frame = MorphFrame(bitmap, floating.box)
        compositeGeckoPixels(floating.geckoView, card, bitmap) { result(frame) }
    }

    /** PixelCopy native Activity chrome, then guarantee the SurfaceView webpage is in the frame. */
    private fun captureActivityFrame(activity: Activity, root: View, attempt: Int, result: (MorphFrame?) -> Unit) {
        if (!root.isLaidOut || root.width <= 0 || root.height <= 0 || activity.isFinishing) {
            if (attempt < 3) root.postOnAnimation { captureActivityFrame(activity, root, attempt + 1, result) }
            else result(null)
            return
        }
        val location = IntArray(2); root.getLocationOnScreen(location)
        val box = WindowBox(location[0], location[1], root.width, root.height)
        val bitmap = try { Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        if (bitmap == null) { result(null); return }

        fun finishBase(base: Bitmap) {
            val gecko = (activity as? BrowserActivity)?.geckoView
            compositeGeckoPixels(gecko, root, base) { result(MorphFrame(base, box)) }
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (code == PixelCopy.SUCCESS) finishBase(bitmap)
                    else {
                        bitmap.safeRecycle()
                        if (attempt < 2) main.postDelayed({ captureActivityFrame(activity, root, attempt + 1, result) }, 24L)
                        else {
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

    /**
     * GeckoView's default fullscreen backend is SurfaceView, while floating uses TextureView.
     * Window/View screenshots are therefore not sufficient as a correctness primitive. Gecko's
     * own capturePixels() returns the current compositor surface regardless of backend. That
     * enhancement is bounded by a hard deadline so a wedged surface handoff can never wedge the UI.
     */
    private fun compositeGeckoPixels(
        gecko: GeckoView?,
        owner: View,
        base: Bitmap,
        done: () -> Unit
    ) {
        if (gecko == null || !gecko.isAttachedToWindow || gecko.width <= 0 || gecko.height <= 0) {
            done(); return
        }
        val finished = AtomicBoolean(false)
        val deadline = SystemClock.uptimeMillis() + GECKO_CAPTURE_TIMEOUT_MS
        lateinit var timeout: Runnable
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            if (Looper.myLooper() === Looper.getMainLooper()) done() else main.post(done)
        }
        timeout = Runnable { finish() }
        main.postDelayed(timeout, GECKO_CAPTURE_TIMEOUT_MS)

        fun attempt(index: Int) {
            if (finished.get()) return
            if (SystemClock.uptimeMillis() >= deadline || !gecko.isAttachedToWindow) {
                finish(); return
            }
            try {
                gecko.capturePixels().accept({ web ->
                    if (finished.get()) {
                        web?.safeRecycle()
                    } else if (web != null && !web.isRecycled) {
                        // Immediately after a surface handoff Gecko can briefly return the previous
                        // surface-sized frame. Prefer a frame matching the destination View geometry.
                        val stale = kotlin.math.abs(web.width - gecko.width) > 8 ||
                            kotlin.math.abs(web.height - gecko.height) > 8
                        val delay = 20L * (index + 1)
                        if (stale && index < 3 && gecko.isAttachedToWindow && SystemClock.uptimeMillis() + delay < deadline) {
                            web.safeRecycle()
                            main.postDelayed({ attempt(index + 1) }, delay)
                        } else {
                            try {
                                val ownerLocation = IntArray(2); owner.getLocationOnScreen(ownerLocation)
                                val geckoLocation = IntArray(2); gecko.getLocationOnScreen(geckoLocation)
                                val left = geckoLocation[0] - ownerLocation[0]
                                val top = geckoLocation[1] - ownerLocation[1]
                                val dest = Rect(left, top, left + gecko.width, top + gecko.height)
                                Canvas(base).drawBitmap(web, null, dest, capturePaint)
                            } catch (_: Throwable) { }
                            web.safeRecycle()
                            finish()
                        }
                    } else finish()
                }, { _ ->
                    if (finished.get()) return@accept
                    val delay = 20L * (index + 1)
                    if (index < 3 && gecko.isAttachedToWindow && SystemClock.uptimeMillis() + delay < deadline) {
                        main.postDelayed({ attempt(index + 1) }, delay)
                    } else finish()
                })
            } catch (_: RuntimeException) {
                val delay = 20L * (index + 1)
                if (index < 3 && gecko.isAttachedToWindow && SystemClock.uptimeMillis() + delay < deadline) {
                    main.postDelayed({ attempt(index + 1) }, delay)
                } else finish()
            }
        }
        attempt(0)
    }

    /** Keep the held fullscreen frame opaque until Android has actually hidden the source task. */
    private fun waitUntilSourceWindowHidden(activity: Activity, root: View, attempt: Int, ready: () -> Unit) {
        // Losing task focus is sufficient because the held overlay already contains the exact source
        // pixels. We do not need to wait for ViewRoot teardown, which can lag task backgrounding and
        // would otherwise add a visible pause before the shrink starts.
        val hidden = activity.isFinishing || !activity.hasWindowFocus() || !root.isAttachedToWindow ||
            root.windowVisibility != View.VISIBLE || !root.isShown
        if (hidden || attempt >= 30) {
            main.post(ready)
        } else {
            main.postDelayed({ waitUntilSourceWindowHidden(activity, root, attempt + 1, ready) }, 16L)
        }
    }

    /** Keep the grown card opaque until the destination window is attached, laid out and drawable. */
    private fun waitUntilDestinationWindowReady(activity: Activity, root: View, attempt: Int, ready: () -> Unit) {
        val stable = !activity.isFinishing && root.isAttachedToWindow && root.isShown && root.isLaidOut &&
            root.width > 0 && root.height > 0 && root.windowVisibility == View.VISIBLE
        if (stable || attempt >= 30) {
            main.post(ready)
        } else {
            main.postDelayed({ waitUntilDestinationWindowReady(activity, root, attempt + 1, ready) }, 16L)
        }
    }

    /** Never leave a full-screen held frame stranded if Android/Gecko misses a callback. */
    private fun scheduleShrinkWatchdog(overlay: WindowMorphOverlay, floating: FloatingWindow) {
        main.postDelayed({
            if (morphOverlay === overlay) {
                showFloating(floating)
                morphOverlay = null
                overlay.detach()
            }
        }, SHRINK_WATCHDOG_MS)
    }

    /** A successful Activity launch must never leave an untouchable full-screen overlay behind. */
    private fun scheduleExpansionWatchdog(overlay: WindowMorphOverlay, floating: FloatingWindow) {
        main.postDelayed({
            if (morphOverlay === overlay && expandingIntoFullscreen) {
                // If BrowserActivity never took ownership, restore the still-attached floating card.
                // Otherwise simply reveal the Activity that is already underneath at identical bounds.
                if (BubbleService.active?.window === floating) showFloating(floating)
                morphOverlay = null
                expandingIntoFullscreen = false
                overlay.detach()
            }
        }, EXPANSION_WATCHDOG_MS)
    }

    private fun drawFallback(root: View): Bitmap? {
        val bitmap = try { Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888) }
        catch (_: Throwable) { return null }
        return try {
            root.draw(Canvas(bitmap)); bitmap
        } catch (_: Throwable) {
            bitmap.safeRecycle(); null
        }
    }

    private fun launchFullscreenDirect(context: Context, intent: Intent) {
        try {
            context.applicationContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: RuntimeException) { }
    }

    private fun cancelPendingFullscreenFrame() {
        pendingFullscreenFrame?.bitmap.safeRecycle(); pendingFullscreenFrame = null
    }

    private fun Bitmap?.safeRecycle() {
        if (this != null && !isRecycled) recycle()
    }
}