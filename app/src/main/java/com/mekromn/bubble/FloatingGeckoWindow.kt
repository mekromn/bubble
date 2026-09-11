package com.mekromn.bubble

import android.annotation.SuppressLint
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
 * Build-115-derived Android-16 native buffer experiment.
 *
 * Floating page pixels follow one zero-copy queue:
 * Gecko -> AImageReader ANativeWindow -> AHardwareBuffer -> ASurfaceControl -> SurfaceFlinger.
 *
 * [NativeBufferHost] is only the Android input/IME/accessibility endpoint. It does not own a render
 * Surface. The producer Surface published to Gecko comes from the AImageReader's ANativeWindow in
 * native code. The AImageReader consumer obtains that exact frame as AHardwareBuffer and submits the
 * same allocation to SurfaceFlinger with acquire/release fences; no CPU lock/readback or pixel copy
 * occurs in Bubble.
 *
 * This experimental transport intentionally requires Android 16/API 36 so it can use
 * ASurfaceTransaction_setBufferWithRelease. There is no fallback on this branch because a fallback
 * would make physical A/B results ambiguous.
 */
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
        // RawSessionBridge remains detached bookkeeping only.
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
        if (Build.VERSION.SDK_INT < 36) {
            Toast.makeText(context, "ANativeWindow + AHardwareBuffer test requires Android 16", Toast.LENGTH_LONG).show()
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
            manager.addView(root, layout)
            params = layout
            lastBox = safe
            attached = true
            if (!host.ensurePipeline(rate)) {
                throw IllegalStateException("Could not create native ANativeWindow/AHardwareBuffer pipeline")
            }
            host.updateScreenOrigin()
            true
        } catch (error: RuntimeException) {
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
            Log.e(TAG, "Could not move native-buffer Gecko window", error)
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
        private val screenOrigin = IntArray(2)

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= 36 && pipelineFrameRate > 0f) {
                ensurePipeline(pipelineFrameRate)
                publishSurfaceIfReady()
            }
            updateScreenOrigin()
        }

        override fun onDetachedFromWindow() {
            releasePipeline()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (Build.VERSION.SDK_INT < 36 || !isAttachedToWindow || w <= 0 || h <= 0) return
            if (nativeHandle != 0L && (w != pipelineWidth || h != pipelineHeight)) {
                recreatePipeline(w, h)
            }
        }

        fun ensurePipeline(rate: Float): Boolean {
            if (Build.VERSION.SDK_INT < 36 || !isAttachedToWindow) return false
            pipelineFrameRate = rate
            if (nativeHandle != 0L && producerSurface?.isValid == true) {
                NativeAhbBridge.nativeSetFrameRate(nativeHandle, rate)
                return true
            }
            return createPipeline(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        private fun ensureOutputControl(): SurfaceControl {
            outputControl?.takeIf { it.isValid }?.let { return it }
            releaseOutputControl()
            val rootControl = rootSurfaceControl
                ?: throw IllegalStateException("Native buffer host has no AttachedSurfaceControl")
            val control = SurfaceControl.Builder()
                .setName("Bubble AHardwareBuffer output")
                .build()
            val transaction = rootControl.buildReparentTransaction(control)
                ?: run {
                    control.release()
                    throw IllegalStateException("Could not attach AHardwareBuffer output layer")
                }
            transaction
                .setLayer(control, 1)
                .setVisibility(control, true)
                .setOpaque(control, true)
                .apply()
            outputControl = control
            return control
        }

        private fun createPipeline(w: Int, h: Int): Boolean {
            if (Build.VERSION.SDK_INT < 36 || !isAttachedToWindow) return false
            val control = ensureOutputControl()
            val handle = NativeAhbBridge.nativeCreate(
                w.coerceAtLeast(1),
                h.coerceAtLeast(1),
                control,
                pipelineFrameRate
            )
            if (handle == 0L) return false
            val surface = NativeAhbBridge.nativeGetProducerSurface(handle)
            if (surface == null || !surface.isValid) {
                surface?.release()
                NativeAhbBridge.nativeDestroy(handle)
                return false
            }
            nativeHandle = handle
            producerSurface = surface
            pipelineWidth = w.coerceAtLeast(1)
            pipelineHeight = h.coerceAtLeast(1)
            publishFailurePosted = false
            return true
        }

        private fun recreatePipeline(w: Int, h: Int) {
            val activeDisplay = display
            if (surfacePublished && activeDisplay != null) {
                runCatching { activeDisplay.surfaceDestroyed() }
            }
            surfacePublished = false
            releaseNativeProducer(keepOutputControl = true)
            if (!createPipeline(w, h)) {
                Toast.makeText(context, "Native buffer resize failed", Toast.LENGTH_LONG).show()
                return
            }
            publishSurfaceIfReady()
        }

        fun releasePipeline() {
            if (surfacePublished) {
                display?.let { runCatching { it.surfaceDestroyed() } }
            }
            surfacePublished = false
            releaseNativeProducer(keepOutputControl = false)
        }

        private fun releaseNativeProducer(keepOutputControl: Boolean) {
            producerSurface?.release()
            producerSurface = null
            val oldHandle = nativeHandle
            nativeHandle = 0L
            if (oldHandle != 0L) {
                runCatching { NativeAhbBridge.nativeDestroy(oldHandle) }
            }
            pipelineWidth = 0
            pipelineHeight = 0
            publishFailurePosted = false
            if (!keepOutputControl) releaseOutputControl()
        }

        private fun releaseOutputControl() {
            val control = outputControl ?: return
            outputControl = null
            runCatching {
                if (control.isValid) SurfaceControl.Transaction().reparent(control, null).apply()
            }
            runCatching { control.release() }
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
                if (pipelineFrameRate <= 0f) {
                    pipelineFrameRate = display?.let { this.display }?.run {
                        this@NativeBufferHost.display?.let { _ -> requestedFrameRate.takeIf { it > 0f } ?: 120f }
                    } ?: 120f
                }
                if (nativeHandle == 0L && isAttachedToWindow) ensurePipeline(pipelineFrameRate)
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
            Log.e(TAG, "Could not bind native-buffer GeckoDisplay", error)
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
                if (!ensurePipeline(pipelineFrameRate.takeIf { it > 0f } ?: 120f)) return
            }
            val androidSurface = producerSurface ?: return
            val w = pipelineWidth.coerceAtLeast(width.coerceAtLeast(1))
            val h = pipelineHeight.coerceAtLeast(height.coerceAtLeast(1))
            try {
                val info = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                    .newSurfaceProvider(this)
                    .size(w, h)
                    .build()
                gecko.surfaceChanged(info)
                surfacePublished = true
                publishFailurePosted = false
                updateScreenOrigin()
            } catch (error: RuntimeException) {
                if (!publishFailurePosted) {
                    publishFailurePosted = true
                    Log.e(TAG, "Could not publish ANativeWindow producer Surface", error)
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
                recreatePipeline(width.coerceAtLeast(1), height.coerceAtLeast(1))
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
        private const val TAG = "BubbleNativeBuffers"
    }
}
