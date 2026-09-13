package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Fast steady-state floating renderer.
 *
 * GeckoView already constructs its SurfaceView backend by default. Keep that original backend
 * instance intact: explicitly re-selecting the SurfaceView backend is not a no-op in the pinned
 * GeckoView. It swaps in a new SurfaceView after the listener was registered on the original holder,
 * so the replacement never reports surfaceChanged() to Gecko and remains bufferless.
 *
 * This host only places Mozilla's original GeckoView/SurfaceView inside Bubble's existing floating
 * ViewRoot, keeps Bubble's refresh-rate request, and exposes one-shot compositor capture for the
 * short fullscreen/floating snapshot transitions. There is no ImageReader/AImage relay, Bubble
 * native consumer, extra output SurfaceControl, TextureView, or page bitmap in steady-state direct
 * browsing.
 */
@SuppressLint("NewApi")
internal class DirectGeckoWindow(private val context: Context) : FloatingPageHost {
    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE

    override val view: LiveGeckoView = LiveGeckoView(context).apply {
        // GeckoView's constructor already created and wired the direct SurfaceView backend.
        // Do not replace that backend here: the pinned implementation would install a fresh holder
        // that has no registered display listener.
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override val pageView: View get() = view

    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        addView(view, FrameLayout.LayoutParams(-1, -1))
    }
    private var container: FrameLayout? = null
    private var coveredForReveal = false
    private var requestedRate = 0f

    /** Attach to Bubble's existing interactive floating window; never create another ViewRoot. */
    override fun show(parent: FrameLayout): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The direct Gecko renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }
        if (container === parent && root.parent === parent) {
            applyRate()
            return true
        }
        if (container != null) hide()
        return try {
            parent.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
            container = parent
            requestedRate = RenderPolicy.vote(context, root).takeIf { it > 0f } ?: 120f
            applyRate()
            view.alpha = if (coveredForReveal) 0f else 1f
            true
        } catch (failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "GeckoView SurfaceView attach failed", failure)
            runCatching { view.releaseSession() }
            (root.parent as? ViewGroup)?.removeView(root)
            container = null
            Toast.makeText(context, "Direct Gecko page failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Called for UI/window animation/layout changes, never from webpage frame production. */
    override fun geometryChanged() {
        if (!root.isAttachedToWindow) return
        applyRate()
        root.invalidate()
        view.postInvalidateOnAnimation()
    }

    /**
     * Keep the real SurfaceView alive and warm while Bubble's brief reveal animation covers it.
     * Alpha is compositor state; using INVISIBLE would tear down/recreate the SurfaceView.
     */
    override fun coverForReveal(covered: Boolean) {
        coveredForReveal = covered
        view.alpha = if (covered) 0f else 1f
        geometryChanged()
    }

    override fun backgroundCutout(): View? =
        view.takeIf { !coveredForReveal && it.isAttachedToWindow && it.width > 0 && it.height > 0 }

    /** One-shot transition snapshot only; never part of steady-state rendering. */
    override fun capturePagePixels(done: (Bitmap?) -> Unit) {
        if (!view.isAttachedToWindow || view.session == null || view.width <= 0 || view.height <= 0) {
            done(null)
            return
        }
        try {
            view.capturePixels().accept({ bitmap -> done(bitmap) }, { _ -> done(null) })
        } catch (_: RuntimeException) {
            done(null)
        }
    }

    override fun hide() {
        runCatching { view.releaseSession() }
        (root.parent as? ViewGroup)?.removeView(root)
        container = null
    }

    override fun destroy() = hide()

    private fun applyRate() {
        val rate = requestedRate.takeIf { it > 0f } ?: return
        RenderPolicy.voteTree(view, rate)
    }
}
