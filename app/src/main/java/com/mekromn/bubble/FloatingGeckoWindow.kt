package com.mekromn.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
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

/** Bubble floating renderer: the user-selected, queued relay_latest_bp transport. */
@SuppressLint("NewApi")
internal class FloatingGeckoWindow(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val host = NativeBufferHost(context)

    val view: LiveGeckoView = RawSessionBridge(context, host).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(host, FrameLayout.LayoutParams(-1, -1))
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "The native floating renderer requires Android 16", Toast.LENGTH_LONG).show()
            return false
        }
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
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
            title = "Bubble ANativeWindow AHardwareBuffer page"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        return try {
            val rate = RenderPolicy.vote(context, host, layout)
            DiagnosticLog.event("NATIVE_BUFFER", "overlay add begin size=${safe.width}x${safe.height} rate=$rate")
            manager.addView(root, layout)
            params = layout
            lastBox = safe
            attached = true
            host.preparePipeline(rate)
            host.updateScreenOrigin()
            DiagnosticLog.event("NATIVE_BUFFER", "overlay attached; compositor parenting deferred")
            true
        } catch (error: RuntimeException) {
            DiagnosticLog.error("NATIVE_BUFFER", "overlay attach failed", error)
            Log.e(TAG, "Could not attach native-buffer Gecko window", error)
            runCatching { if (root.isAttachedToWindow) manager.removeViewImmediate(root) }
            host.releasePipeline()
            params = null
            lastBox = null
            attached = false
            Toast.makeText(context, "Native buffer window failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun sync(box: WindowBox) {
        if (!attached) return
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        if (lastBox == safe) return
        val layout = params ?: return
        layout.x = safe.x
        layout.y = safe.y
        layout.width = safe.width
        layout.height = safe.height
        try {
            manager.updateViewLayout(root, layout)
            lastBox = safe
            host.updateScreenOrigin()
        } catch (error: RuntimeException) {
            DiagnosticLog.error("NATIVE_BUFFER", "overlay move failed", error)
            hide()
        }
    }

    fun hide() {
        view.releaseSession()
        host.releasePipeline()
        if (!attached) return
        attached = false
        params = null
        lastBox = null
        runCatching { manager.removeViewImmediate(root) }
    }

    fun destroy() = hide()

    private class RawSessionBridge(
        context: Context,
        private val raw: NativeBufferHost
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

    @SuppressLint("NewApi")
    private class NativeBufferHost(context: Context) : View(context), GeckoDisplay.NewSurfaceProvider {
        private var session: GeckoSession? = null
        private var display: GeckoDisplay? = null
        private var accessibilityHost: View? = null
        private var outputControl: SurfaceControl? = null
        private var nativeHandle: Long = 0L
        private var producerSurface: Surface? = null
        private var pipelineWidth = 0
        private var pipelineHeight = 0
        private var pipelineFrameRate = 0f
        private var surfacePublished = false
        private var publishFailurePosted = false
        private var pipelineRetryPosted = false
        private var pipelineRetryCount = 0
        private var pipelineFailureNotified = false
        private val screenOrigin = IntArray(2)
        @Volatile private var generation = 0L
        private var creating = false
        private val main = Handler(Looper.getMainLooper())
        private var creationTimeout: Runnable? = null

        // Only attachment-readiness retries use Android's frame callback. There is
        // NO per-frame Java/JNI acquisition or presentation pump.
        private val pipelineRetry = Runnable {
            pipelineRetryPosted = false
            if (isAttachedToWindow && session != null && !creating && nativeHandle == 0L) {
                if (width > 0 && height > 0) startPipeline(width, height)
                else schedulePipelineStart()
            }
        }

        init { setBackgroundColor(Color.TRANSPARENT); isFocusable = true; isFocusableInTouchMode = true }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow(); schedulePipelineStart(); updateScreenOrigin()
        }
        override fun onDetachedFromWindow() {
            releasePipeline(); super.onDetachedFromWindow()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if ((nativeHandle != 0L || creating) && (w != pipelineWidth || h != pipelineHeight)) releasePipeline()
            schedulePipelineStart(); updateScreenOrigin()
        }
        fun preparePipeline(frameRate: Float) {
            pipelineFrameRate = frameRate.takeIf { it > 0f } ?: 120f
            if (nativeHandle != 0L) NativeAhbBridge.nativeSetFrameRate(nativeHandle, pipelineFrameRate)
            else schedulePipelineStart()
        }
        private fun schedulePipelineStart() {
            if (!isAttachedToWindow || Build.VERSION.SDK_INT < 36 || session == null ||
                creating || nativeHandle != 0L || pipelineRetryPosted || pipelineFailureNotified) return
            if (++pipelineRetryCount > MAX_PIPELINE_RETRIES) {
                failPipeline("Android did not expose a ready output layer"); return
            }
            pipelineRetryPosted = true; postOnAnimation(pipelineRetry)
        }
        private fun startPipeline(w: Int, h: Int) {
            val parentControl = rootSurfaceControl ?: run { schedulePipelineStart(); return }
            val control = try {
                SurfaceControl.Builder().setName("Bubble relay_latest_bp generation ${generation + 1}")
                    .setBufferSize(w, h).build()
            } catch (e: RuntimeException) { failPipeline(e.javaClass.simpleName); return }
            val transaction = try { parentControl.buildReparentTransaction(control) }
                catch (e: RuntimeException) { control.release(); failPipeline(e.javaClass.simpleName); return }
            if (transaction == null) { control.release(); schedulePipelineStart(); return }
            try {
                transaction.setLayer(control, 1).setVisibility(control, true).setOpaque(control, true)
                    .setCrop(control, android.graphics.Rect(0, 0, w, h)).apply()
            } catch (e: RuntimeException) {
                control.release(); failPipeline(e.javaClass.simpleName); return
            } finally { transaction.close() }
            val ticket = ++generation
            outputControl = control; creating = true; pipelineWidth = w; pipelineHeight = h
            val rate = pipelineFrameRate
            creationTimeout = Runnable {
                if (generation == ticket && creating) failPipeline("Native producer creation timed out")
            }.also { main.postDelayed(it, 10_000) }
            creator.execute {
                var handle = 0L
                var surface: Surface? = null
                var failure: String? = null
                try {
                    if (generation == ticket) {
                        handle = NativeAhbBridge.nativeCreate(w, h, control, rate)
                        if (handle != 0L) surface = NativeAhbBridge.nativeGetProducerSurface(handle)
                        if (handle == 0L || surface?.isValid != true) failure = "Native producer unavailable"
                    }
                } catch (e: Exception) { failure = e.javaClass.simpleName }
                catch (e: LinkageError) { failure = e.javaClass.simpleName }
                val createdHandle = handle; val createdSurface = surface; val error = failure
                main.post {
                    // A resize, tab switch, or hide can complete while native setup is
                    // in flight. That old generation must never publish into a new tab.
                    if (generation != ticket || !isAttachedToWindow || session == null) {
                        createdSurface?.release()
                        if (createdHandle != 0L) NativeAhbBridge.nativeDestroy(createdHandle)
                        detachAndRelease(control, true)
                    } else {
                        creationTimeout?.let(main::removeCallbacks); creationTimeout = null
                        creating = false; nativeHandle = createdHandle; producerSurface = createdSurface
                        if (error != null) failPipeline(error)
                        else { pipelineRetryCount = 0; publishSurfaceIfReady() }
                    }
                }
            }
        }
        private fun detachAndRelease(control: SurfaceControl, release: Boolean) {
            runCatching { SurfaceControl.Transaction().use { it.reparent(control, null).apply() } }
            if (release) runCatching { control.release() }
        }
        private fun failPipeline(message: String) {
            releasePipeline(); pipelineFailureNotified = true
            Log.e(TAG, "relay_latest_bp: $message")
            Toast.makeText(context, "Floating renderer failed: $message", Toast.LENGTH_LONG).show()
        }
        fun releasePipeline() {
            ++generation
            removeCallbacks(pipelineRetry); pipelineRetryPosted = false
            creationTimeout?.let(main::removeCallbacks); creationTimeout = null
            if (surfacePublished) runCatching { display?.surfaceDestroyed() }
            surfacePublished = false; publishFailurePosted = false
            producerSurface?.release(); producerSurface = null
            if (nativeHandle != 0L) NativeAhbBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
            // During creation the completion callback owns the Java control reference;
            // dropping it early would race ASurfaceControl_fromJava on the worker.
            outputControl?.let { detachAndRelease(it, !creating) }; outputControl = null
            creating = false; pipelineWidth = 0; pipelineHeight = 0
            pipelineRetryCount = 0; pipelineFailureNotified = false
        }

        fun bind(next: GeckoSession): Boolean {
            if (session === next && display != null) {
                publishSurfaceIfReady()
                return true
            }
            session?.let(::unbind)
            return try {
                session = next
                configureInput(next)
                display = next.acquireDisplay()
                if (pipelineFrameRate <= 0f) pipelineFrameRate = requestedFrameRate.takeIf { it > 0f } ?: 120f
                schedulePipelineStart()
                publishSurfaceIfReady()
                updateScreenOrigin()
                DiagnosticLog.event("NATIVE_BUFFER", "GeckoDisplay bound; waiting for producer if needed")
                display != null
            } catch (error: RuntimeException) {
                cleanupAfterBindFailure(next, error)
                false
            }
        }

        fun unbind(expected: GeckoSession) {
            if (session !== expected) return
            releasePipeline()
            val oldDisplay = display
            val oldAccessibilityHost = accessibilityHost
            if (surfacePublished && oldDisplay != null) runCatching { oldDisplay.surfaceDestroyed() }
            surfacePublished = false
            publishFailurePosted = false
            display = null
            session = null
            accessibilityHost = null
            runCatching { if (expected.textInput.view === this) expected.textInput.setView(null) }
            runCatching {
                val active = expected.accessibility.view
                if (active === oldAccessibilityHost || active === this) expected.accessibility.setView(null)
            }
            if (oldDisplay != null) runCatching { expected.releaseDisplay(oldDisplay) }
        }

        private fun cleanupAfterBindFailure(target: GeckoSession, error: RuntimeException) {
            DiagnosticLog.error("NATIVE_BUFFER", "GeckoDisplay bind failed", error)
            releasePipeline()
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
            Toast.makeText(context, "Native buffer Gecko display failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        private fun configureInput(target: GeckoSession) {
            target.textInput.setView(this)
            val parentView = parent as? View
                ?: throw IllegalStateException("Native buffer host is missing accessibility parent")
            check(parentView is ViewParent) { "Native buffer accessibility host must implement ViewParent" }
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

        fun publishSurfaceIfReady() {
            val gecko = display ?: return
            val current = session ?: return
            if (!isAttachedToWindow) return
            if (nativeHandle == 0L || producerSurface?.isValid != true) {
                schedulePipelineStart()
                return
            }
            if (surfacePublished) return
            val androidSurface = producerSurface ?: return
            val w = pipelineWidth
            val h = pipelineHeight
            if (w != width || h != height || w <= 0 || h <= 0) {
                releasePipeline(); schedulePipelineStart(); return
            }
            try {
                val info = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                    .newSurfaceProvider(this)
                    .size(w, h)
                    .build()
                DiagnosticLog.event("NATIVE_BUFFER", "Gecko surfaceChanged begin size=${w}x$h")
                gecko.surfaceChanged(info)
                surfacePublished = true
                publishFailurePosted = false
                updateScreenOrigin()
                DiagnosticLog.event("NATIVE_BUFFER", "Gecko surfaceChanged success; relay_latest_bp native consumer active")
            } catch (error: RuntimeException) {
                if (!publishFailurePosted) {
                    publishFailurePosted = true
                    DiagnosticLog.error("NATIVE_BUFFER", "producer Surface publish failed", error)
                    post {
                        if (session === current) {
                            unbind(current)
                            Toast.makeText(context, "Native producer surface failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                    }
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
                if (!isAttachedToWindow || Build.VERSION.SDK_INT < 36) return@post
                releasePipeline(); schedulePipelineStart()
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

        companion object {
            private const val MAX_PIPELINE_RETRIES = 120
            private val creator = Executors.newSingleThreadExecutor { task ->
                Thread(task, "BubbleRelaySetup").apply { isDaemon = true }
            }
        }
    }

    companion object {
        private const val TAG = "BubbleNativeBuffers"
    }
}
