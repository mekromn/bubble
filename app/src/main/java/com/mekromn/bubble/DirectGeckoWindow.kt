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
        // Preserve the constructor-wired direct SurfaceView for the lifetime of this host.
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

    /** Attach to Bubble's existing interactive floating window; never create another ViewRoot. */
    override fun show(parent: FrameLayout): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The direct Gecko renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }
        if (container === parent && root.parent === parent) return true
        if (container != null) hide()
        return try {
            parent.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
            container = parent
            // One vote installs the SurfaceHolder lifecycle callback. Surface recreation is handled
            // by that callback; ordinary parent geometry animation never needs to re-vote.
            RenderPolicy.vote(context, root)
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
        // Geometry synchronization still needs a UI-frame invalidation for SurfaceView transforms,
        // but frame-rate contracts are stable and are deliberately not touched here.
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
}
