package com.mekromn.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewParent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession

/**
 * Experimental direct-compositor floating Gecko display.
 *
 * This branch intentionally starts from Build 115 and changes only the floating page transport.
 * Instead of Gecko -> SurfaceView -> BLAST/Surface -> SurfaceFlinger, Gecko renders into a Surface
 * constructed directly from an app-owned SurfaceControl child of the page window's root compositor
 * hierarchy. The only Android View left in the page window is a lightweight input/IME/accessibility
 * host; it does not own the rendering Surface and does not copy webpage pixels.
 *
 * Android 12/API 31 is the minimum for attaching an app-created SurfaceControl through the public
 * AttachedSurfaceControl API. Bubble's primary target is Android 16; on older Android versions this
 * experimental transport refuses to attach rather than silently falling back and invalidating the
 * A/B test.
 */
internal class FloatingGeckoWindow(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val surface = DirectGeckoSurfaceHost(context)
    val view: LiveGeckoView = RawSessionBridge(context, surface).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(surface, FrameLayout.LayoutParams(-1, -1))
        // RawSessionBridge remains bookkeeping only and is never attached to a Window.
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(context, "Direct compositor test requires Android 12+", Toast.LENGTH_LONG).show()
            return false
        }

        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        DiagnosticLog.event("DIRECT_WINDOW", "show requested box=$safe attached=$attached")
        if (attached) {
            sync(safe)
            return true
        }

        val layout = WindowManager.LayoutParams(
            safe.width,
            safe.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = safe.x
            y = safe.y
            title = "Bubble direct Gecko SurfaceControl"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return try {
            val rate = RenderPolicy.vote(context, surface, layout)
            surface.producerFrameRate = rate
            manager.addView(root, layout)
            params = layout
            lastBox = safe
            attached = true
            surface.ensureProducerNow()
            surface.updateScreenOrigin()
            DiagnosticLog.event("DIRECT_WINDOW", "attached root=${id(root)} input=${id(surface)} rate=$rate")
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach direct Gecko compositor window", error)
            DiagnosticLog.error("DIRECT_WINDOW", "addView/direct producer failed box=$safe", error)
            runCatching { if (root.isAttachedToWindow) manager.removeViewImmediate(root) }
            params = null
            lastBox = null
            attached = false
            Toast.makeText(context, "Direct Gecko window failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun sync(box: WindowBox) {
        if (!attached) return
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        if (lastBox == safe) return
        val layout = params ?: return
        val previous = lastBox
        layout.x = safe.x
        layout.y = safe.y
        layout.width = safe.width
        layout.height = safe.height
        try {
            manager.updateViewLayout(root, layout)
            lastBox = safe
            if (previous?.width != safe.width || previous.height != safe.height) {
                surface.resizeProducer(safe.width, safe.height)
            }
            surface.updateScreenOrigin()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not move direct Gecko compositor window", error)
            DiagnosticLog.error("DIRECT_WINDOW", "updateViewLayout failed from=$previous to=$safe", error)
            hide()
        }
    }

    fun hide() {
        DiagnosticLog.event("DIRECT_WINDOW", "hide begin attached=$attached")
        // Release GeckoDisplay while the producer Surface/SurfaceControl are still valid.
        view.releaseSession()
        if (!attached) {
            surface.releaseProducer()
            return
        }
        attached = false
        params = null
        lastBox = null
        runCatching { manager.removeViewImmediate(root) }
            .onFailure { DiagnosticLog.error("DIRECT_WINDOW", "removeViewImmediate failed", it) }
        surface.releaseProducer()
    }

    fun destroy() = hide()

    private class RawSessionBridge(
        context: Context,
        private val raw: DirectGeckoSurfaceHost
    ) : LiveGeckoView(context) {
        private var bound: GeckoSession? = null

        override fun getSession(): GeckoSession? = bound

        override fun setSession(session: GeckoSession) {
            if (bound === session) {
                raw.publishSurfaceIfReady()
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

    /**
     * A plain View that owns input/IME/accessibility only. Webpage pixels bypass View rendering and
     * are produced into [producerSurface], whose [producerControl] is attached directly under this
     * Window's root SurfaceControl.
     */
    private class DirectGeckoSurfaceHost(context: Context) : View(context), GeckoDisplay.NewSurfaceProvider {
        private var session: GeckoSession? = null
        private var display: GeckoDisplay? = null
        private var accessibilityHost: View? = null
        private var producerControl: SurfaceControl? = null
        private var producerSurface: Surface? = null
        private var surfacePublished = false
        private var producerWidth = 0
        private var producerHeight = 0
        private val screenOrigin = IntArray(2)
        private var publishFailurePosted = false
        var producerFrameRate: Float = 0f

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ensureProducerNow()
            publishSurfaceIfReady()
            updateScreenOrigin()
        }

        override fun onDetachedFromWindow() {
            // Normal hide() unbinds first. This extra guard handles external WindowManager teardown.
            val current = session
            if (surfacePublished && current != null) {
                display?.let { runCatching { it.surfaceDestroyed() } }
                surfacePublished = false
            }
            releaseProducer()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && (w != producerWidth || h != producerHeight)) {
                resizeProducer(w, h)
            }
        }

        fun ensureProducerNow() {
            if (Build.VERSION.SDK_INT < 31 || !isAttachedToWindow) return
            val currentControl = producerControl
            val currentSurface = producerSurface
            if (currentControl != null && currentControl.isValid && currentSurface?.isValid == true) return

            releaseProducer()
            val width = this.width.coerceAtLeast(1)
            val height = this.height.coerceAtLeast(1)
            val rootControl = rootSurfaceControl
                ?: throw IllegalStateException("Direct Gecko input host has no AttachedSurfaceControl")

            val control = SurfaceControl.Builder()
                .setName("Bubble direct Gecko buffers")
                .setBufferSize(width, height)
                .build()
            val androidSurface = Surface(control)

            val attach = rootControl.buildReparentTransaction(control)
                ?: throw IllegalStateException("Could not build direct Gecko reparent transaction")
            attach
                .setLayer(control, 1)
                .setBufferSize(control, width, height)
                .setVisibility(control, true)
            if (Build.VERSION.SDK_INT >= 33) {
                attach.setOpaque(control, true)
            }
            attach.apply()

            producerControl = control
            producerSurface = androidSurface
            producerWidth = width
            producerHeight = height
            applyFrameRate(androidSurface)
            DiagnosticLog.event(
                "DIRECT_SURFACE",
                "created control=${id(control)} surface=${id(androidSurface)} size=${width}x$height rate=$producerFrameRate"
            )
            publishSurfaceIfReady()
        }

        fun resizeProducer(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < 31) return
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            if (producerWidth == w && producerHeight == h) return
            ensureProducerNow()
            val control = producerControl ?: return
            if (!control.isValid) return
            SurfaceControl.Transaction()
                .setBufferSize(control, w, h)
                .apply()
            producerWidth = w
            producerHeight = h
            publishSurfaceIfReady()
        }

        fun releaseProducer() {
            val androidSurface = producerSurface
            val control = producerControl
            producerSurface = null
            producerControl = null
            producerWidth = 0
            producerHeight = 0
            if (control != null && control.isValid) {
                runCatching { SurfaceControl.Transaction().reparent(control, null).apply() }
            }
            runCatching { androidSurface?.release() }
            runCatching { control?.release() }
        }

        fun bind(next: GeckoSession): Boolean {
            if (session === next && display != null) {
                ensureProducerNow()
                publishSurfaceIfReady()
                return true
            }
            session?.let(::unbind)
            return try {
                session = next
                configureInput(next)
                display = next.acquireDisplay()
                ensureProducerNow()
                publishSurfaceIfReady()
                updateScreenOrigin()
                display != null
            } catch (error: RuntimeException) {
                cleanupAfterBindFailure(next, error)
                false
            }
        }

        fun unbind(expected: GeckoSession) {
            if (session !== expected) return
            val oldDisplay = display
            val oldAccessibilityHost = accessibilityHost
            if (surfacePublished && oldDisplay != null) {
                runCatching { oldDisplay.surfaceDestroyed() }
            }
            surfacePublished = false
            publishFailurePosted = false
            display = null
            session = null
            accessibilityHost = null
            runCatching {
                if (expected.textInput.view === this) expected.textInput.setView(null)
            }
            runCatching {
                val active = expected.accessibility.view
                if (active === oldAccessibilityHost || active === this) expected.accessibility.setView(null)
            }
            if (oldDisplay != null) runCatching { expected.releaseDisplay(oldDisplay) }
        }

        private fun cleanupAfterBindFailure(target: GeckoSession, error: RuntimeException) {
            Log.e(TAG, "Could not acquire/publish direct GeckoDisplay", error)
            DiagnosticLog.error("DIRECT_DISPLAY", "bind failure target=${id(target)}", error)
            val oldDisplay = display
            val oldAccessibilityHost = accessibilityHost
            if (surfacePublished && oldDisplay != null) runCatching { oldDisplay.surfaceDestroyed() }
            surfacePublished = false
            publishFailurePosted = false
            display = null
            session = null
            accessibilityHost = null
            runCatching { if (target.textInput.view === this) target.textInput.setView(null) }
            runCatching {
                val active = target.accessibility.view
                if (active === oldAccessibilityHost || active === this) target.accessibility.setView(null)
            }
            if (oldDisplay != null) runCatching { target.releaseDisplay(oldDisplay) }
            Toast.makeText(context, "Direct Gecko display failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        private fun configureInput(target: GeckoSession) {
            target.textInput.setView(this)
            val host = parent as? View
                ?: throw IllegalStateException("Direct Gecko host is missing its accessibility parent")
            check(host is ViewParent) { "Direct Gecko accessibility host must implement ViewParent" }
            accessibilityHost = host
            target.accessibility.setView(host)
            val metrics = resources.displayMetrics
            val value = TypedValue()
            val factor = if (context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, value, true)) {
                value.getDimension(metrics)
            } else {
                0.075f * metrics.densityDpi
            }
            target.panZoomController.setScrollFactor(factor)
        }

        fun publishSurfaceIfReady() {
            val gecko = display ?: return
            val current = session ?: return
            if (!isAttachedToWindow) return
            ensureProducerNow()
            val androidSurface = producerSurface ?: return
            val control = producerControl ?: return
            if (!androidSurface.isValid || !control.isValid) return
            val width = producerWidth.coerceAtLeast(this.width.coerceAtLeast(1))
            val height = producerHeight.coerceAtLeast(this.height.coerceAtLeast(1))
            try {
                val info = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                    .newSurfaceProvider(this)
                    .size(width, height)
                    .surfaceControl(control)
                    .build()
                gecko.surfaceChanged(info)
                surfacePublished = true
                publishFailurePosted = false
                updateScreenOrigin()
            } catch (error: RuntimeException) {
                if (!publishFailurePosted) {
                    publishFailurePosted = true
                    Log.e(TAG, "Could not publish direct Gecko Surface", error)
                    DiagnosticLog.error("DIRECT_SURFACE", "surfaceChanged failed", error)
                    post {
                        if (session === current) {
                            unbind(current)
                            Toast.makeText(context, "Direct Gecko surface failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        private fun applyFrameRate(surface: Surface) {
            if (producerFrameRate <= 0f || Build.VERSION.SDK_INT < 30) return
            runCatching {
                val compatibility = if (Build.VERSION.SDK_INT >= 36) {
                    Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST
                } else {
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    surface.setFrameRate(
                        producerFrameRate,
                        compatibility,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    )
                } else {
                    surface.setFrameRate(producerFrameRate, compatibility)
                }
            }
        }

        fun updateScreenOrigin() {
            val gecko = display ?: return
            if (!isAttachedToWindow) return
            getLocationOnScreen(screenOrigin)
            runCatching { gecko.screenOriginChanged(screenOrigin[0], screenOrigin[1]) }
        }

        override fun requestNewSurface() {
            post {
                val current = session
                if (surfacePublished) display?.let { runCatching { it.surfaceDestroyed() } }
                surfacePublished = false
                releaseProducer()
                ensureProducerNow()
                if (current != null && session === current) publishSurfaceIfReady()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val current = session ?: return false
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

        override fun onDragEvent(event: DragEvent): Boolean {
            return session?.panZoomController?.onDragEvent(event) ?: super.onDragEvent(event)
        }

        override fun onCheckIsTextEditor(): Boolean = session != null

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            return session?.textInput?.onCreateInputConnection(outAttrs)
        }

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

    companion object {
        private const val TAG = "BubbleDirectGecko"
        private fun id(value: Any?): String = if (value == null) "null" else Integer.toHexString(System.identityHashCode(value))
    }
}
