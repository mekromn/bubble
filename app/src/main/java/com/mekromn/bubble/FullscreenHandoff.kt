package com.mekromn.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View

/**
 * Matched Activity <-> overlay handoff.
 *
 * The previous implementations animated the two real windows independently, which can never read
 * as a single object: Android is free to expose one surface before the other and Gecko can repaint
 * at a different cadence. This coordinator freezes one exact frame, puts that frame in a temporary
 * transparent system overlay, and morphs *that one visible card* between the two geometries. The
 * real destination surface is prepared underneath and is revealed only after its pixels occupy the
 * exact same bounds. No black backing frame, no double card, no per-frame Gecko resize.
 */
internal object FullscreenHandoff {
    const val EXTRA_FROM_FLOATING = "bubble.transition.from.floating"
    private val main = Handler(Looper.getMainLooper())
    private var pendingFullscreenFrame: MorphFrame? = null
    private var morphOverlay: WindowMorphOverlay? = null
    private var expandingIntoFullscreen = false

    /** Capture the fullscreen Activity before the service steals the GeckoSession. */
    fun armFullscreenToFloating(activity: Activity, root: View, ready: (Boolean) -> Unit) {
        cancelPendingFullscreenFrame()
        captureActivityFrame(activity, root, 0) { frame ->
            pendingFullscreenFrame = frame
            ready(frame != null)
        }
    }

    fun shouldSuppressDirectFloatingEntrance(): Boolean = pendingFullscreenFrame != null

    /**
     * Fullscreen -> floating. The source Activity remains visually frozen in the morph overlay;
     * once that overlay has committed its first frame, the Activity can safely move behind the
     * launcher. The user then sees the same rectangle contract continuously into the exact saved
     * floating bounds while the destination chrome/page crossfades inside those same bounds.
     */
    fun shrinkFullscreen(activity: Activity, root: View, target: WindowBox, done: () -> Unit) {
        reset(root)
        val source = pendingFullscreenFrame
        pendingFullscreenFrame = null
        val floating = BubbleService.active?.window?.takeIf { it.mode == FloatingMode.CHAT }
        if (source == null || floating == null) {
            root.postOnAnimation { done() }
            return
        }
        holdFloating(floating)
        root.postOnAnimation {
            if (activity.isFinishing) {
                source.bitmap.safeRecycle(); showFloating(floating); done(); return@postOnAnimation
            }
            val destination = captureFloatingFrame(floating)
            val exactTarget = floating.box.takeIf { it.width > 0 && it.height > 0 } ?: target
            val overlay = WindowMorphOverlay(
                activity.applicationContext,
                source,
                exactTarget,
                0f,
                Ui.dp(activity, 26f).toFloat()
            )
            morphOverlay?.detach(); morphOverlay = overlay
            overlay.attach {
                // The temporary card now contains the exact fullscreen pixels, so exposing the
                // launcher underneath cannot create a hole or a black frame.
                destination?.let(overlay::setDestination)
                done()
                main.postOnAnimation {
                    overlay.morph(365L) {
                        showFloating(floating)
                        if (morphOverlay === overlay) morphOverlay = null
                        overlay.detach()
                    }
                }
            }
        }
    }

    /**
     * Floating -> fullscreen. First grow the already-visible floating card all the way to the
     * display while the launcher remains visible around it. Only once the card physically covers
     * the display do we launch BrowserActivity behind the held frame. The Activity then supplies a
     * real destination screenshot for a short final chrome/content dissolve at identical bounds.
     */
    fun expandFloatingToFullscreen(context: Context, floating: FloatingWindow, intent: Intent) {
        if (expandingIntoFullscreen || morphOverlay != null) return
        val source = captureFloatingFrame(floating)
        if (source == null) {
            launchFullscreenDirect(context, intent)
            return
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
        expandingIntoFullscreen = true
        overlay.attach {
            holdFloating(floating)
            overlay.morph(380L) {
                // Keep the full-screen frozen card above everything while BrowserActivity starts.
                // System Activity animation may happen underneath; it is never visible to the user.
                val launch = Intent(intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
    }

    /** Called by BrowserActivity after it has attached the existing session to its fullscreen view. */
    fun finishIntoFullscreen(activity: Activity, root: View) {
        if (!expandingIntoFullscreen) return
        val overlay = morphOverlay ?: run { expandingIntoFullscreen = false; return }
        // Two compositor frames give Gecko/toolbar/insets a chance to occupy their final geometry.
        root.postOnAnimation {
            root.postOnAnimation {
                captureActivityFrame(activity, root, 0) { destination ->
                    overlay.finishWith(destination, 115L) {
                        if (morphOverlay === overlay) morphOverlay = null
                        expandingIntoFullscreen = false
                        overlay.detach()
                    }
                }
            }
        }
    }

    fun isEnteringFullscreen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FLOATING, false) == true && expandingIntoFullscreen

    /** BubbleService funnels floating fullscreen launches here so the outer card, not Gecko alone, morphs. */
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

    private fun captureFloatingFrame(floating: FloatingWindow): MorphFrame? {
        val card = floating.transitionView
        if (!card.isLaidOut || card.width <= 0 || card.height <= 0) return null
        val oldAlpha = card.alpha
        val oldSx = card.scaleX; val oldSy = card.scaleY
        val oldTx = card.translationX; val oldTy = card.translationY
        card.alpha = 1f; card.scaleX = 1f; card.scaleY = 1f; card.translationX = 0f; card.translationY = 0f
        val bitmap = try { Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        if (bitmap == null) {
            card.alpha = oldAlpha; card.scaleX = oldSx; card.scaleY = oldSy; card.translationX = oldTx; card.translationY = oldTy
            return null
        }
        return try {
            card.draw(Canvas(bitmap))
            MorphFrame(bitmap, floating.box)
        } catch (_: Throwable) {
            bitmap.safeRecycle(); null
        } finally {
            card.alpha = oldAlpha; card.scaleX = oldSx; card.scaleY = oldSy; card.translationX = oldTx; card.translationY = oldTy
        }
    }

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
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (code == PixelCopy.SUCCESS) result(MorphFrame(bitmap, box))
                    else {
                        bitmap.safeRecycle()
                        if (attempt < 2) main.postDelayed({ captureActivityFrame(activity, root, attempt + 1, result) }, 24L)
                        else result(drawFallback(root, box))
                    }
                }, main)
                return
            } catch (_: RuntimeException) { }
        }
        bitmap.safeRecycle()
        result(drawFallback(root, box))
    }

    private fun drawFallback(root: View, box: WindowBox): MorphFrame? {
        val bitmap = try { Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888) }
        catch (_: Throwable) { return null }
        return try {
            root.draw(Canvas(bitmap)); MorphFrame(bitmap, box)
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
