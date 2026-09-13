package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession

/**
 * Direct Gecko transport for the renderer arena.
 *
 * Gecko receives this SurfaceView's REAL holder Surface together with the matching
 * SurfaceControl, exactly through GeckoDisplay.SurfaceInfo. There is no ImageReader,
 * AImage acquisition loop, Bubble AHardwareBuffer relay, native worker, or extra output
 * SurfaceControl in this transport. Android/Gecko still own their normal BufferQueue and
 * synchronization; this is not front-buffer rendering.
 */
@SuppressLint("NewApi")
internal class DirectGeckoWindow(private val context: Context) : FloatingPageHost {
    private val host = DirectSurfaceHost(context)

    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE
    override val pageView: View get() = host

    override val view: LiveGeckoView = RawSessionBridge(context, host).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        addView(host, FrameLayout.LayoutParams(-1, -1))
    }
    private var container: FrameLayout? = null

    override fun show(parent: FrameLayout): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The direct Gecko renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }
        if (container === parent && root.parent === parent) {
            host.publishSurfaceIfReady(false)
            return true
        }
        if (container != null) hide()
        return try {
            parent.addView(root, 0, FrameLayout.LayoutParams(-1, -1))
            container = parent
            host.prepareFrameRate(RenderPolicy.vote(context, root))
            host.publishSurfaceIfReady(false)
            true
        } catch (failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "embedded host attach failed", failure)
            (root.parent as? ViewGroup)?.removeView(root)
            container = null
            Toast.makeText(context, "Direct Gecko page failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun geometryChanged() { host.rootView.invalidate() }
    override fun coverForReveal(covered: Boolean) {
        host.coveredForReveal = covered
        geometryChanged()
    }
    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }
    override fun capturePagePixels(done: (Bitmap?) -> Unit) = host.capturePagePixels(done)

    override fun hide() {
        view.releaseSession()
        (root.parent as? ViewGroup)?.removeView(root)
        container = null
    }

    override fun destroy() = hide()

    private class RawSessionBridge(
        context: Context,
        private val raw: DirectSurfaceHost
    ) : LiveGeckoView(context) {
        private var bound: GeckoSession? = null
        override fun getSession(): GeckoSession? = bound
        private val focusHost: View? = raw
        override fun hasWindowFocus(): Boolean = focusHost?.hasWindowFocus() ?: false

        override fun setSession(session: GeckoSession) {
            if (bound === session) {
                raw.publishSurfaceIfReady(false)
                return
            }
            releaseSession()
            if (raw.bind(session)) bound = session
        }

        override fun releaseSession(): GeckoSession? {
            val old = bound ?: return null
            bound = null
            raw.unbind(old)
            return old
        }
    }

    @SuppressLint("NewApi")
    private class DirectSurfaceHost(context: Context) : SurfaceView(context),
        GeckoDisplay.NewSurfaceProvider, SurfaceHolder.Callback {

        private var surfaceReady = false
        private var session: GeckoSession? = null
        private var display: GeckoDisplay? = null
        private var accessibilityHost: View? = null
        private var surfacePublished = false
        private var publishFailurePosted = false
        private var requestedRate = 0f
        private var publishedWidth = 0
        private var publishedHeight = 0
        private val screenOrigin = IntArray(2)
        private var originDisplay: GeckoDisplay? = null
        private var originX = Int.MIN_VALUE
        private var originY = Int.MIN_VALUE
        private val placement = EmbeddedSurfacePlacement()
        private var lastPlacement: EmbeddedSurfacePlacement.Value? = null
        private var observedTree: ViewTreeObserver? = null
        var coveredForReveal = false
        val hasLiveLayer: Boolean get() = surfacePublished && surfaceReady && isAttachedToWindow

        private val beforeChromeDraw = ViewTreeObserver.OnPreDrawListener {
            updateScreenOrigin()
            syncLayerWithChrome()
            true
        }

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            setZOrderOnTop(false)
            // Match GeckoView's own SurfaceView backend rather than silently changing
            // webpage alpha/color behavior for the arena arm.
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = holder.surface?.isValid == true
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = holder.surface?.isValid == true
            if (requestedRate > 0f) RenderPolicy.voteTree(this, requestedRate)
            publishSurfaceIfReady(true)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            if (surfacePublished) runCatching { display?.surfaceDestroyed() }
            surfacePublished = false
            publishedWidth = 0
            publishedHeight = 0
            lastPlacement = null
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            observedTree = viewTreeObserver.also { it.addOnPreDrawListener(beforeChromeDraw) }
            surfaceReady = holder.surface?.isValid == true
            publishSurfaceIfReady(false)
            updateScreenOrigin()
        }

        override fun onDetachedFromWindow() {
            observedTree?.let { if (it.isAlive) it.removeOnPreDrawListener(beforeChromeDraw) }
            observedTree = null
            if (surfacePublished) runCatching { display?.surfaceDestroyed() }
            surfacePublished = false
            lastPlacement = null
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && (w != publishedWidth || h != publishedHeight)) {
                publishSurfaceIfReady(true)
            }
            updateScreenOrigin()
        }

        fun prepareFrameRate(rate: Float) {
            requestedRate = rate.takeIf { it > 0f } ?: 120f
            // Use Bubble's single Android-16 UI/scroll contract. It chooses
            // FRAME_RATE_COMPATIBILITY_AT_LEAST and avoids a second conflicting vote.
            RenderPolicy.voteTree(this, requestedRate)
        }

        fun bind(next: GeckoSession): Boolean {
            if (session === next && display != null) {
                publishSurfaceIfReady(false)
                return true
            }
            session?.let(::unbind)
            return try {
                session = next
                configureInput(next)
                display = next.acquireDisplay()
                if (requestedRate <= 0f) requestedRate = requestedFrameRate.takeIf { it > 0f } ?: 120f
                if (requestedRate > 0f) RenderPolicy.voteTree(this, requestedRate)
                publishSurfaceIfReady(false)
                updateScreenOrigin()
                display != null
            } catch (failure: RuntimeException) {
                cleanupAfterBindFailure(next, failure)
                false
            }
        }

        fun unbind(expected: GeckoSession) {
            if (session !== expected) return
            val oldDisplay = display
            val oldAccessibilityHost = accessibilityHost
            if (surfacePublished && oldDisplay != null) runCatching { oldDisplay.surfaceDestroyed() }
            surfacePublished = false
            publishFailurePosted = false
            publishedWidth = 0
            publishedHeight = 0
            lastPlacement = null
            display = null
            originDisplay = null
            session = null
            accessibilityHost = null
            runCatching { if (expected.textInput.view === this) expected.textInput.setView(null) }
            runCatching {
                val active = expected.accessibility.view
                if (active === oldAccessibilityHost || active === this) expected.accessibility.setView(null)
            }
            if (oldDisplay != null) runCatching { expected.releaseDisplay(oldDisplay) }
        }

        private fun cleanupAfterBindFailure(target: GeckoSession, failure: RuntimeException) {
            if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "GeckoDisplay bind failed", failure)
            val oldDisplay = display
            val oldAccessibilityHost = accessibilityHost
            if (surfacePublished && oldDisplay != null) runCatching { oldDisplay.surfaceDestroyed() }
            surfacePublished = false
            publishFailurePosted = false
            publishedWidth = 0
            publishedHeight = 0
            display = null
            originDisplay = null
            session = null
            accessibilityHost = null
            runCatching { if (target.textInput.view === this) target.textInput.setView(null) }
            runCatching {
                val active = target.accessibility.view
                if (active === oldAccessibilityHost || active === this) target.accessibility.setView(null)
            }
            if (oldDisplay != null) runCatching { target.releaseDisplay(oldDisplay) }
            Toast.makeText(context, "Direct Gecko display failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        private fun configureInput(target: GeckoSession) {
            target.textInput.setView(this)
            val parentView = parent as? View
                ?: throw IllegalStateException("Direct Gecko host is missing accessibility parent")
            check(parentView is ViewParent) { "Direct Gecko accessibility host must implement ViewParent" }
            accessibilityHost = parentView
            target.accessibility.setView(parentView)
            val metrics = resources.displayMetrics
            val value = TypedValue()
            val factor = if (context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, value, true)) {
                value.getDimension(metrics)
            } else {
                0.075f * metrics.densityDpi
            }
            target.panZoomController.setScrollFactor(factor)
        }

        fun publishSurfaceIfReady(force: Boolean) {
            val gecko = display ?: return
            val current = session ?: return
            if (!isAttachedToWindow || !surfaceReady) return
            val androidSurface = holder.surface ?: return
            if (!androidSurface.isValid) return
            val control = surfaceControl
            if (control?.isValid != true) return
            val frame = holder.surfaceFrame
            val w = frame.width().takeIf { it > 0 } ?: width
            val h = frame.height().takeIf { it > 0 } ?: height
            if (w <= 0 || h <= 0) return
            if (surfacePublished && !force && w == publishedWidth && h == publishedHeight) return
            try {
                val info = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                    .surfaceControl(control)
                    .newSurfaceProvider(this)
                    .size(w, h)
                    .build()
                gecko.surfaceChanged(info)
                surfacePublished = true
                publishFailurePosted = false
                publishedWidth = w
                publishedHeight = h
                lastPlacement = null
                rootView.invalidate()
                updateScreenOrigin()
            } catch (failure: RuntimeException) {
                if (!publishFailurePosted) {
                    publishFailurePosted = true
                    if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "surface publish failed", failure)
                    post {
                        if (session === current) {
                            unbind(current)
                            Toast.makeText(context, "Direct Gecko surface failed: ${failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        private fun syncLayerWithChrome() {
            if (!isAttachedToWindow || !surfaceReady) return
            val control = surfaceControl ?: return
            if (!control.isValid) return
            val value = placement.read(this, coveredForReveal)
            if (value == lastPlacement) return
            val tx = SurfaceControl.Transaction()
            try {
                tx.setAlpha(control, value.alpha)
                    .setVisibility(control, value.visible)
                val attached = rootSurfaceControl
                if (attached != null && attached.applyTransactionOnDraw(tx)) {
                    lastPlacement = value
                } else {
                    tx.apply()
                    lastPlacement = value
                }
            } catch (failure: RuntimeException) {
                if (DiagnosticLog.ENABLED) DiagnosticLog.error("DIRECT_GECKO", "surface visibility sync failed", failure)
            } finally {
                tx.close()
            }
        }

        fun capturePagePixels(done: (Bitmap?) -> Unit) {
            val gecko = display
            if (gecko == null || !surfacePublished) { done(null); return }
            try {
                gecko.capturePixels().accept({ image -> done(image) }, { _ -> done(null) })
            } catch (_: RuntimeException) { done(null) }
        }

        fun updateScreenOrigin() {
            val gecko = display ?: return
            if (!isAttachedToWindow) return
            getLocationOnScreen(screenOrigin)
            val x = screenOrigin[0]
            val y = screenOrigin[1]
            if (originDisplay === gecko && x == originX && y == originY) return
            runCatching { gecko.screenOriginChanged(x, y) }.onSuccess {
                originDisplay = gecko
                originX = x
                originY = y
            }
        }

        override fun requestNewSurface() {
            post {
                if (!isAttachedToWindow || session == null) return@post
                // GeckoView itself uses SurfaceView recreation for this recovery contract.
                // Toggling this page host is exceptional recovery only, never a frame loop.
                val prior = visibility
                visibility = View.INVISIBLE
                post {
                    if (isAttachedToWindow && session != null) visibility = prior
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val current = session ?: return false
            PageTouchDispatch.request(this, event, true)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
            current.panZoomController.onTouchEvent(event)
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            val current = session ?: return super.onGenericMotionEvent(event)
            if (current.accessibility.onMotionEvent(event)) return true
            current.panZoomController.onMotionEvent(event)
            return true
        }

        override fun onDragEvent(event: DragEvent): Boolean =
            session?.panZoomController?.onDragEvent(event) ?: super.onDragEvent(event)

        override fun onCheckIsTextEditor(): Boolean = session != null
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            session?.textInput?.onCreateInputConnection(outAttrs)

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (super.onKeyPreIme(keyCode, event)) return true
            return session?.textInput?.onKeyPreIme(keyCode, event) ?: false
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (super.onKeyDown(keyCode, event)) return true
            return session?.textInput?.onKeyDown(keyCode, event) ?: false
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (super.onKeyUp(keyCode, event)) return true
            return session?.textInput?.onKeyUp(keyCode, event) ?: false
        }

        override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
            if (super.onKeyLongPress(keyCode, event)) return true
            return session?.textInput?.onKeyLongPress(keyCode, event) ?: false
        }

        override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
            if (super.onKeyMultiple(keyCode, repeatCount, event)) return true
            return session?.textInput?.onKeyMultiple(keyCode, repeatCount, event) ?: false
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            Workspace.peek()?.applyPolicy()
        }
    }
}
