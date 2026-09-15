package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Direct floating Gecko host with one Window/ViewRoot for page + Bubble chrome.
 *
 * GeckoView already owns the correct SurfaceView backend. Keep that exact instance intact and place
 * it directly inside FloatingWindow's page slot. The SurfaceView remains a separately composed child
 * SurfaceControl, but it now shares the same ViewRoot traversal, window transaction, input/focus
 * hierarchy and geometry owner as the browser chrome.
 *
 * Build 158 deliberately removes the independent page TYPE_APPLICATION_OVERLAY introduced in 156:
 * no second WindowManager.addView/updateViewLayout/removeView path, no cross-window geometry sampler,
 * no pre-draw/layout synchronization hooks, no duplicate Back dispatcher, no second IME policy and no
 * page-window move animation. Window motion/resizing is therefore one WindowManager transaction for
 * the whole browser card; Android synchronizes Gecko's child SurfaceView with that ViewRoot.
 *
 * Steady-state page pixels are still Gecko/WebRender -> Mozilla SurfaceView -> SurfaceFlinger. There
 * is no ImageReader relay, TextureView, bitmap copy, page blit or quality reduction. The Android 16
 * ADPF experiment remains bound to the same real Gecko producer Surface so this build isolates the
 * window/ViewRoot architecture rather than changing scheduler policy at the same time.
 */
@SuppressLint("NewApi")
internal class DirectGeckoWindow(private val context: Context) : FloatingPageHost {
    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE

    override val view: LiveGeckoView = LiveGeckoView(context).apply {
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

    /** Attach once to FloatingWindow's existing page slot; repeat renders are true no-ops. */
    override fun show(parent: FrameLayout): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The direct Gecko renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }
        if (container === parent && root.parent === parent) return true

        FloatingPerformancePolicy.unbind()
        (root.parent as? ViewGroup)?.removeView(root)
        container = null
        return try {
            parent.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
            container = parent
            requestedRate = RenderPolicy.vote(context, root)
            view.alpha = if (coveredForReveal) 0f else 1f
            // Gecko creates/owns the SurfaceView. Bind only after attachment so holder/display state
            // is real; SurfaceHolder callbacks then own the rest of the Surface lifetime.
            root.post {
                if (container === parent && root.parent === parent && root.isAttachedToWindow) {
                    bindPerformance()
                }
            }
            true
        } catch (failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "Single-ViewRoot Gecko attach failed", failure)
            FloatingPerformancePolicy.unbind()
            runCatching { view.releaseSession() }
            (root.parent as? ViewGroup)?.removeView(root)
            container = null
            requestedRate = 0f
            Toast.makeText(context, "Direct Gecko page failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Same-ViewRoot motion needs no page-window transaction. This hook exists only because short
     * native chrome/content animations may change the background cutout transform; one invalidation
     * is enough and there is no callback during steady webpage scrolling.
     */
    override fun geometryChanged() {
        container?.postInvalidateOnAnimation()
    }

    /** Keep Gecko's real SurfaceView alive while a one-shot transition frame covers it. */
    override fun coverForReveal(covered: Boolean) {
        if (coveredForReveal == covered) return
        coveredForReveal = covered
        view.alpha = if (covered) 0f else 1f
        container?.postInvalidateOnAnimation()
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
        FloatingPerformancePolicy.unbind()
        runCatching { view.releaseSession() }
        (root.parent as? ViewGroup)?.removeView(root)
        container = null
        requestedRate = 0f
    }

    override fun destroy() = hide()

    /** Find Mozilla's existing SurfaceView without replacing/reconfiguring its backend. */
    private fun surfaceView(node: View): SurfaceView? {
        if (node is SurfaceView) return node
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) {
                surfaceView(node.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun bindPerformance() {
        val rate = requestedRate.takeIf { it > 0f } ?: return
        surfaceView(root)?.let { FloatingPerformancePolicy.bind(it, rate) }
    }
}
