package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fast steady-state floating renderer.
 *
 * GeckoView already constructs its SurfaceView backend by default. Keep that original backend
 * instance intact: explicitly re-selecting the SurfaceView backend is not a no-op in the pinned
 * GeckoView. It swaps in a new SurfaceView after the listener was registered on the original holder,
 * so the replacement never reports surfaceChanged() to Gecko and remains bufferless.
 *
 * Build 156 gives the live page its own small opaque TYPE_APPLICATION_OVERLAY window instead of
 * making the page a child of Bubble's large translucent chrome window. The top/bottom floating
 * chrome stays in FloatingWindow; this page window occupies only the exact middle page rectangle.
 * That gives SurfaceFlinger an honest opaque page layer which can fully occlude the underlying app
 * and the transparent middle of Bubble chrome, while keeping Mozilla's original SurfaceView direct
 * path and the exact same GeckoSession.
 *
 * There is still no ImageReader/AImage relay, Bubble native consumer, TextureView, extra page blit or
 * steady-state page bitmap. If Android rejects the independent overlay window, this class falls back
 * to the previous same-ViewRoot direct SurfaceView path rather than breaking browsing.
 */
@SuppressLint("NewApi")
internal class DirectGeckoWindow(private val context: Context) : FloatingPageHost {
    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE

    private val manager = context.getSystemService(WindowManager::class.java)

    override val view: LiveGeckoView = LiveGeckoView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override val pageView: View get() = view

    private val root = FrameLayout(context).apply {
        addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private val pageParams = WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = 0
        y = 0
        alpha = 1f
        dimAmount = 0f
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        title = "Bubble opaque floating page"
    }

    private data class PageGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

    private var container: FrameLayout? = null
    private var overlayAttached = false
    private var embeddedFallback = false
    private var coveredForReveal = false
    private var syncQueued = false
    private var lastGeometry: PageGeometry? = null
    private val transform = Matrix()
    private val bounds = RectF()
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null

    /**
     * The chrome tree redraws when WindowManager moves/resizes the floating panel. Sampling its
     * transformed page bounds at pre-draw keeps the independent page window aligned without any
     * idle timer or polling loop. Identical geometry is skipped before updateViewLayout().
     */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        syncPageWindow(false)
        true
    }

    /** Attach the real Gecko page as a separate opaque overlay above Bubble's transparent page hole. */
    override fun show(parent: FrameLayout): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The direct Gecko renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }

        if (container !== parent) {
            detachContainerHooks()
            if (root.parent is ViewGroup) (root.parent as ViewGroup).removeView(root)
            container = parent
            installContainerHooks(parent)
        }

        if (embeddedFallback) return showEmbedded(parent)

        val geometry = geometryOf(parent)
        if (geometry != null) {
            if (!overlayAttached && !attachOverlay(geometry)) return showEmbedded(parent)
            syncPageWindow(false)
        } else {
            // The parent can be attached one traversal before it has non-zero bounds. Do not create
            // a 1x1 Gecko surface; wait for the first laid-out pre-draw/post instead.
            parent.post { if (container === parent && !overlayAttached && !embeddedFallback) {
                geometryOf(parent)?.let { if (!attachOverlay(it)) showEmbedded(parent) }
            } }
        }
        return true
    }

    private fun installContainerHooks(parent: FrameLayout) {
        val observer = parent.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(preDraw)
    }

    private fun detachContainerHooks() {
        val old = container ?: return
        val observer = old.viewTreeObserver
        if (observer.isAlive) runCatching { observer.removeOnPreDrawListener(preDraw) }
    }

    private fun attachOverlay(geometry: PageGeometry): Boolean {
        if (overlayAttached) return true
        return try {
            setOpaqueBacking(true)
            applyGeometry(geometry)
            pageParams.alpha = if (coveredForReveal) 0f else 1f
            RenderPolicy.vote(context, root, pageParams)
            manager.addView(root, pageParams)
            overlayAttached = true
            lastGeometry = geometry
            root.post { registerBackCallback() }
            true
        } catch (failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "Opaque page overlay attach failed", failure)
            runCatching { if (overlayAttached) manager.removeViewImmediate(root) }
            overlayAttached = false
            lastGeometry = null
            embeddedFallback = true
            false
        }
    }

    /** Previous production direct path retained only as a reliability fallback. */
    private fun showEmbedded(parent: FrameLayout): Boolean {
        embeddedFallback = true
        if (overlayAttached) {
            unregisterBackCallback()
            runCatching { manager.removeViewImmediate(root) }
            overlayAttached = false
        }
        setOpaqueBacking(false)
        if (root.parent !== parent) {
            (root.parent as? ViewGroup)?.removeView(root)
            return try {
                parent.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
                RenderPolicy.vote(context, root)
                view.alpha = if (coveredForReveal) 0f else 1f
                true
            } catch (failure: RuntimeException) {
                if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "Embedded Gecko SurfaceView fallback failed", failure)
                runCatching { view.releaseSession() }
                (root.parent as? ViewGroup)?.removeView(root)
                Toast.makeText(context, "Direct Gecko page failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                false
            }
        }
        view.alpha = if (coveredForReveal) 0f else 1f
        return true
    }

    private fun setOpaqueBacking(opaque: Boolean) {
        val color = if (opaque) Ui.BG else Color.TRANSPARENT
        root.setBackgroundColor(color)
        view.setBackgroundColor(color)
    }

    private fun geometryOf(parent: View): PageGeometry? {
        if (!parent.isAttachedToWindow || parent.width <= 0 || parent.height <= 0) return null
        transform.reset()
        parent.transformMatrixToGlobal(transform)
        bounds.set(0f, 0f, parent.width.toFloat(), parent.height.toFloat())
        transform.mapRect(bounds)
        val left = floor(bounds.left.toDouble()).toInt()
        val top = floor(bounds.top.toDouble()).toInt()
        val right = ceil(bounds.right.toDouble()).toInt()
        val bottom = ceil(bounds.bottom.toDouble()).toInt()
        return PageGeometry(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    private fun applyGeometry(geometry: PageGeometry) {
        pageParams.x = geometry.x
        pageParams.y = geometry.y
        pageParams.width = geometry.width
        pageParams.height = geometry.height
    }

    /** Called for floating-window motion/layout changes, never from webpage frame production. */
    override fun geometryChanged() {
        if (embeddedFallback) {
            if (!root.isAttachedToWindow) return
            root.invalidate()
            view.postInvalidateOnAnimation()
            return
        }
        scheduleSync()
    }

    private fun scheduleSync() {
        if (syncQueued) return
        val parent = container ?: return
        syncQueued = true
        parent.postOnAnimation {
            syncQueued = false
            if (container === parent) syncPageWindow(false)
        }
    }

    private fun syncPageWindow(force: Boolean) {
        if (embeddedFallback) return
        val parent = container ?: return
        val geometry = geometryOf(parent) ?: return
        if (!overlayAttached) {
            attachOverlay(geometry)
            return
        }
        val desiredAlpha = if (coveredForReveal) 0f else 1f
        if (!force && lastGeometry == geometry && pageParams.alpha == desiredAlpha) return
        applyGeometry(geometry)
        pageParams.alpha = desiredAlpha
        try {
            manager.updateViewLayout(root, pageParams)
            lastGeometry = geometry
        } catch (failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "Opaque page overlay geometry update failed", failure)
            embeddedFallback = true
            unregisterBackCallback()
            runCatching { manager.removeViewImmediate(root) }
            overlayAttached = false
            lastGeometry = null
            showEmbedded(parent)
        }
    }

    /**
     * Keep Gecko's real SurfaceView alive and warm while the frozen handoff frame covers it.
     * The independent page Window uses compositor alpha instead of INVISIBLE so its SurfaceView is
     * not destroyed/recreated during the transition.
     */
    override fun coverForReveal(covered: Boolean) {
        coveredForReveal = covered
        if (embeddedFallback) {
            view.alpha = if (covered) 0f else 1f
            geometryChanged()
        } else {
            syncPageWindow(true)
        }
    }

    /**
     * FloatingWindow only needs the cutout geometry. In opaque-overlay mode use the same-ViewRoot
     * page container as the cutout source; the real GeckoView lives in another Window now.
     */
    override fun backgroundCutout(): View? {
        val parent = container
        return if (!embeddedFallback) {
            parent?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        } else {
            view.takeIf { !coveredForReveal && it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        }
    }

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

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || !overlayAttached) return
        unregisterBackCallback()
        val dispatcher = root.findOnBackInvokedDispatcher() ?: return
        val callback = android.window.OnBackInvokedCallback {
            val imeVisible = ViewCompat.getRootWindowInsets(root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            when {
                imeVisible -> context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                Workspace.peek()?.quickMenuVisible == true -> container?.let { QuickPanel.dismissFor(it) }
                else -> BubbleService.active?.window?.collapse()
            }
        }
        backDispatcher = dispatcher
        backCallback = callback
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    private fun unregisterBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = backDispatcher
            val callback = backCallback
            if (dispatcher != null && callback != null) runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        backDispatcher = null
        backCallback = null
    }

    override fun hide() {
        runCatching { view.releaseSession() }
        unregisterBackCallback()
        detachContainerHooks()
        if (overlayAttached) runCatching { manager.removeViewImmediate(root) }
        else (root.parent as? ViewGroup)?.removeView(root)
        overlayAttached = false
        lastGeometry = null
        syncQueued = false
        container = null
    }

    override fun destroy() {
        hide()
    }
}
