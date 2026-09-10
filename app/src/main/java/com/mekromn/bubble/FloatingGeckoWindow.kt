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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession

/**
 * Raw floating Gecko display.
 *
 * Floating rendering deliberately bypasses GeckoView's SurfaceView/TextureView display wrapper.
 * GeckoSession.acquireDisplay() is connected straight to a plain Android SurfaceView through
 * GeckoDisplay.surfaceChanged(SurfaceInfo). This is the same low-level display API GeckoView itself
 * ultimately uses, but it removes GeckoView's overlay-sensitive surface lifecycle from the hot path.
 *
 * Workspace still needs a GeckoView-shaped object for its existing session handoff/focus bookkeeping,
 * so a 1x1 INVISIBLE RawSessionBridge lives in the same window. It never calls GeckoView.setSession(),
 * never acquires a Gecko display and never renders. Its overridden setSession/releaseSession methods
 * bind/unbind the raw SurfaceView instead. The real pixels therefore have exactly one display surface.
 */
internal class FloatingGeckoWindow(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val surface = RawGeckoSurfaceView(context)
    val view: LiveGeckoView = RawSessionBridge(context, surface).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(surface, FrameLayout.LayoutParams(-1, -1))
        addView(view, FrameLayout.LayoutParams(1, 1))
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
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
            title = "Bubble raw Gecko surface"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return try {
            RenderPolicy.vote(context, surface, layout)
            manager.addView(root, layout)
            params = layout
            lastBox = safe
            attached = true
            surface.updateScreenOrigin()
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach raw Gecko surface window", error)
            Toast.makeText(context, "Raw Gecko window failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            params = null
            lastBox = null
            attached = false
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
            surface.updateScreenOrigin()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not move raw Gecko surface window", error)
            hide()
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        params = null
        lastBox = null
        runCatching { manager.removeViewImmediate(root) }
    }

    fun destroy() = hide()

    private class RawSessionBridge(
        context: Context,
        private val raw: RawGeckoSurfaceView
    ) : LiveGeckoView(context) {
        private var bound: GeckoSession? = null

        override fun getSession(): GeckoSession? = bound

        override fun setSession(session: GeckoSession) {
            if (bound === session) return
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

    private class RawGeckoSurfaceView(context: Context) : SurfaceView(context),
        SurfaceHolder.Callback, GeckoDisplay.NewSurfaceProvider {

        private var session: GeckoSession? = null
        private var display: GeckoDisplay? = null
        private var surfacePublished = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private val screenOrigin = IntArray(2)

        init {
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(this)
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
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
                publishSurfaceIfReady()
                updateScreenOrigin()
                true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not acquire raw GeckoDisplay", error)
                display = null
                session = null
                Toast.makeText(context, "Raw Gecko display failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                false
            }
        }

        fun unbind(expected: GeckoSession) {
            if (session !== expected) return
            val oldDisplay = display
            if (surfacePublished && oldDisplay != null) runCatching { oldDisplay.surfaceDestroyed() }
            surfacePublished = false
            display = null
            session = null
            if (expected.textInput.view === this) expected.textInput.setView(null)
            if (expected.accessibility.view === this) expected.accessibility.setView(null)
            if (oldDisplay != null) runCatching { expected.releaseDisplay(oldDisplay) }
        }

        private fun configureInput(target: GeckoSession) {
            target.textInput.setView(this)
            target.accessibility.setView(this)
            val metrics = resources.displayMetrics
            val value = TypedValue()
            val factor = if (context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, value, true)) {
                value.getDimension(metrics)
            } else {
                0.075f * metrics.densityDpi
            }
            target.panZoomController.setScrollFactor(factor)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            publishSurfaceIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            publishSurfaceIfReady()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfacePublished) display?.let { runCatching { it.surfaceDestroyed() } }
            surfacePublished = false
            surfaceWidth = 0
            surfaceHeight = 0
        }

        private fun publishSurfaceIfReady() {
            val gecko = display ?: return
            val androidSurface = holder.surface
            if (!androidSurface.isValid) return
            val width = (if (surfaceWidth > 0) surfaceWidth else this.width).coerceAtLeast(1)
            val height = (if (surfaceHeight > 0) surfaceHeight else this.height).coerceAtLeast(1)
            val builder = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                .newSurfaceProvider(this)
                .size(width, height)
            if (Build.VERSION.SDK_INT >= 29) builder.surfaceControl(surfaceControl)
            gecko.surfaceChanged(builder.build())
            surfacePublished = true
            updateScreenOrigin()
        }

        fun updateScreenOrigin() {
            val gecko = display ?: return
            if (!isAttachedToWindow) return
            getLocationOnScreen(screenOrigin)
            gecko.screenOriginChanged(screenOrigin[0], screenOrigin[1])
        }

        override fun requestNewSurface() {
            post {
                visibility = View.INVISIBLE
                visibility = View.VISIBLE
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
        private const val TAG = "BubbleRawGecko"
    }
}
