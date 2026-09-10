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
 * Workspace still needs a GeckoView-shaped object for its existing session handoff bookkeeping.
 * RawSessionBridge therefore exists only as a DETACHED adapter object: it is never inserted into a
 * View hierarchy or attached to a Window. That distinction is important. GeckoView has private
 * session/display lifecycle state that our raw display does not own, so letting the adapter receive
 * GeckoView window callbacks creates a split-brain lifecycle and can crash during fullscreen ->
 * floating handoff. The only real attached page View is RawGeckoSurfaceView below.
 *
 * The raw SurfaceView is intentionally Z-ordered above its own dedicated page-only overlay window.
 * SurfaceView normally composes behind its containing Window; that is useful in ordinary Activities
 * because Android punches a hole through the app window, but the target Pixel showed our opaque
 * TYPE_APPLICATION_OVERLAY as solid black. Since this dedicated overlay contains only webpage pixels
 * (the glass header/footer live in separate windows), putting the SurfaceView above this one Window is
 * safe and removes any dependence on overlay-window hole punching while preserving the direct Surface
 * compositor path.
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
        // Deliberately DO NOT add RawSessionBridge. It is bookkeeping only, never a window child.
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var lastBox: WindowBox? = null

    fun show(box: WindowBox): Boolean {
        val safe = box.copy(width = box.width.coerceAtLeast(1), height = box.height.coerceAtLeast(1))
        DiagnosticLog.event("RAW_WINDOW", "show requested box=$safe attached=$attached ${DiagnosticLog.selectedState()}")
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
            DiagnosticLog.event("RAW_WINDOW", "addView begin box=$safe root=${id(root)} surface=${id(surface)}")
            manager.addView(root, layout)
            params = layout
            lastBox = safe
            attached = true
            DiagnosticLog.event("RAW_WINDOW", "addView success attached=${root.isAttachedToWindow} surfaceAttached=${surface.isAttachedToWindow}")
            surface.updateScreenOrigin()
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach raw Gecko surface window", error)
            DiagnosticLog.error("RAW_WINDOW", "addView failed box=$safe", error)
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
        val previous = lastBox
        layout.x = safe.x
        layout.y = safe.y
        layout.width = safe.width
        layout.height = safe.height
        try {
            manager.updateViewLayout(root, layout)
            lastBox = safe
            // Avoid per-frame disk-noise while dragging. Geometry is only diagnostically interesting
            // when the Surface dimensions change; simple x/y motion still reaches screenOriginChanged.
            if (previous?.width != safe.width || previous.height != safe.height) {
                DiagnosticLog.event("RAW_WINDOW", "resize from=$previous to=$safe surface=${surface.width}x${surface.height}")
            }
            surface.updateScreenOrigin()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not move raw Gecko surface window", error)
            DiagnosticLog.error("RAW_WINDOW", "updateViewLayout failed from=$previous to=$safe", error)
            hide()
        }
    }

    fun hide() {
        DiagnosticLog.event("RAW_WINDOW", "hide begin attached=$attached ${DiagnosticLog.sessionLabel(view.session)}")
        // Release GeckoDisplay while the Android Surface is still valid. This also makes hide()
        // idempotently clean up a partially-attached/bind-failed window instead of leaking ownership.
        view.releaseSession()
        if (!attached) {
            DiagnosticLog.event("RAW_WINDOW", "hide complete already-detached")
            return
        }
        attached = false
        params = null
        lastBox = null
        runCatching { manager.removeViewImmediate(root) }
            .onFailure { DiagnosticLog.error("RAW_WINDOW", "removeViewImmediate failed", it) }
        DiagnosticLog.event("RAW_WINDOW", "hide complete rootAttached=${root.isAttachedToWindow}")
    }

    fun destroy() {
        DiagnosticLog.event("RAW_WINDOW", "destroy")
        hide()
    }

    private class RawSessionBridge(
        context: Context,
        private val raw: RawGeckoSurfaceView
    ) : LiveGeckoView(context) {
        private var bound: GeckoSession? = null

        override fun getSession(): GeckoSession? = bound

        override fun setSession(session: GeckoSession) {
            DiagnosticLog.event(
                "RAW_BRIDGE",
                "setSession requested new=${DiagnosticLog.sessionLabel(session)} old=${DiagnosticLog.sessionLabel(bound)} ${DiagnosticLog.selectedState()}"
            )
            if (bound === session) {
                DiagnosticLog.event("RAW_BRIDGE", "setSession no-op same session=${id(session)}")
                return
            }
            releaseSession()
            val success = raw.bind(session)
            if (success) bound = session
            DiagnosticLog.event(
                "RAW_BRIDGE",
                "setSession result success=$success bound=${DiagnosticLog.sessionLabel(bound)} requested=${DiagnosticLog.sessionLabel(session)}"
            )
        }

        override fun releaseSession(): GeckoSession? {
            val old = bound
            if (old == null) {
                DiagnosticLog.event("RAW_BRIDGE", "releaseSession no-op bound=null")
                return null
            }
            DiagnosticLog.event("RAW_BRIDGE", "releaseSession begin ${DiagnosticLog.sessionLabel(old)}")
            bound = null
            raw.unbind(old)
            DiagnosticLog.event("RAW_BRIDGE", "releaseSession complete old=${id(old)}")
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
        private var publishFailurePosted = false

        init {
            // This MUST happen before the containing Window is added. The page Surface then composes
            // above this dedicated page-only overlay instead of relying on SurfaceView's normal
            // behind-window hole-punch path, which stayed black on the Pixel 9 Pro XL.
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(this)
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            DiagnosticLog.event("RAW_SURFACE", "constructed view=${id(this)} zOnTop=true")
        }

        fun bind(next: GeckoSession): Boolean {
            DiagnosticLog.event(
                "RAW_DISPLAY",
                "bind begin next=${DiagnosticLog.sessionLabel(next)} current=${DiagnosticLog.sessionLabel(session)} display=${id(display)} " +
                    "published=$surfacePublished holderValid=${holder.surface.isValid} attached=$isAttachedToWindow view=${width}x$height holder=${surfaceWidth}x$surfaceHeight"
            )
            if (session === next && display != null) {
                DiagnosticLog.event("RAW_DISPLAY", "bind same-session republish display=${id(display)}")
                publishSurfaceIfReady()
                return display != null
            }
            session?.let {
                DiagnosticLog.event("RAW_DISPLAY", "bind must unbind previous ${DiagnosticLog.sessionLabel(it)}")
                unbind(it)
            }
            return try {
                session = next
                DiagnosticLog.event("RAW_DISPLAY", "configureInput begin ${DiagnosticLog.sessionLabel(next)}")
                configureInput(next)
                DiagnosticLog.event("RAW_DISPLAY", "acquireDisplay begin ${DiagnosticLog.sessionLabel(next)}")
                display = next.acquireDisplay()
                DiagnosticLog.event("RAW_DISPLAY", "acquireDisplay success display=${id(display)} ${DiagnosticLog.sessionLabel(next)}")
                publishSurfaceIfReady()
                updateScreenOrigin()
                val success = display != null
                DiagnosticLog.event("RAW_DISPLAY", "bind complete success=$success display=${id(display)} published=$surfacePublished")
                success
            } catch (error: RuntimeException) {
                cleanupAfterBindFailure(next, error)
                false
            }
        }

        fun unbind(expected: GeckoSession) {
            if (session !== expected) {
                DiagnosticLog.event(
                    "RAW_DISPLAY",
                    "unbind ignored expected=${DiagnosticLog.sessionLabel(expected)} actual=${DiagnosticLog.sessionLabel(session)} display=${id(display)}"
                )
                return
            }
            val oldDisplay = display
            DiagnosticLog.event(
                "RAW_DISPLAY",
                "unbind begin ${DiagnosticLog.sessionLabel(expected)} display=${id(oldDisplay)} published=$surfacePublished holderValid=${holder.surface.isValid} attached=$isAttachedToWindow"
            )
            if (surfacePublished && oldDisplay != null) {
                runCatching { oldDisplay.surfaceDestroyed() }
                    .onSuccess { DiagnosticLog.event("RAW_DISPLAY", "surfaceDestroyed notified display=${id(oldDisplay)}") }
                    .onFailure { DiagnosticLog.error("RAW_DISPLAY", "surfaceDestroyed notify failed display=${id(oldDisplay)}", it) }
            }
            surfacePublished = false
            publishFailurePosted = false
            display = null
            session = null
            runCatching {
                if (expected.textInput.view === this) expected.textInput.setView(null)
            }.onFailure { DiagnosticLog.error("RAW_INPUT", "textInput.setView(null) failed ${id(expected)}", it) }
            runCatching {
                if (expected.accessibility.view === this) expected.accessibility.setView(null)
            }.onFailure { DiagnosticLog.error("RAW_INPUT", "accessibility.setView(null) failed ${id(expected)}", it) }
            if (oldDisplay != null) {
                runCatching { expected.releaseDisplay(oldDisplay) }
                    .onSuccess { DiagnosticLog.event("RAW_DISPLAY", "releaseDisplay success session=${id(expected)} display=${id(oldDisplay)}") }
                    .onFailure { DiagnosticLog.error("RAW_DISPLAY", "releaseDisplay failed session=${id(expected)} display=${id(oldDisplay)}", it) }
            }
            DiagnosticLog.event("RAW_DISPLAY", "unbind complete session=${id(expected)} oldDisplay=${id(oldDisplay)}")
        }

        private fun cleanupAfterBindFailure(target: GeckoSession, error: RuntimeException) {
            Log.e(TAG, "Could not acquire/publish raw GeckoDisplay", error)
            DiagnosticLog.error(
                "RAW_DISPLAY",
                "bind failure target=${DiagnosticLog.sessionLabel(target)} display=${id(display)} published=$surfacePublished holderValid=${holder.surface.isValid}",
                error
            )
            val oldDisplay = display
            if (surfacePublished && oldDisplay != null) {
                runCatching { oldDisplay.surfaceDestroyed() }
                    .onFailure { DiagnosticLog.error("RAW_DISPLAY", "cleanup surfaceDestroyed failed display=${id(oldDisplay)}", it) }
            }
            surfacePublished = false
            publishFailurePosted = false
            display = null
            session = null
            runCatching { if (target.textInput.view === this) target.textInput.setView(null) }
                .onFailure { DiagnosticLog.error("RAW_INPUT", "cleanup textInput detach failed", it) }
            runCatching { if (target.accessibility.view === this) target.accessibility.setView(null) }
                .onFailure { DiagnosticLog.error("RAW_INPUT", "cleanup accessibility detach failed", it) }
            if (oldDisplay != null) {
                runCatching { target.releaseDisplay(oldDisplay) }
                    .onFailure { DiagnosticLog.error("RAW_DISPLAY", "cleanup releaseDisplay failed display=${id(oldDisplay)}", it) }
            }
            Toast.makeText(context, "Raw Gecko display failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
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
            DiagnosticLog.event("RAW_INPUT", "configured session=${id(target)} factor=$factor view=${id(this)}")
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            DiagnosticLog.event(
                "RAW_SURFACE",
                "surfaceCreated surface=${id(holder.surface)} valid=${holder.surface.isValid} view=${width}x$height session=${DiagnosticLog.sessionLabel(session)} display=${id(display)}"
            )
            publishSurfaceIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            DiagnosticLog.event(
                "RAW_SURFACE",
                "surfaceChanged surface=${id(holder.surface)} valid=${holder.surface.isValid} format=$format size=${width}x$height " +
                    "session=${DiagnosticLog.sessionLabel(session)} display=${id(display)} published=$surfacePublished"
            )
            publishSurfaceIfReady()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            DiagnosticLog.event(
                "RAW_SURFACE",
                "surfaceDestroyed callback surface=${id(holder.surface)} published=$surfacePublished session=${DiagnosticLog.sessionLabel(session)} display=${id(display)}"
            )
            if (surfacePublished) {
                display?.let { activeDisplay ->
                    runCatching { activeDisplay.surfaceDestroyed() }
                        .onSuccess { DiagnosticLog.event("RAW_DISPLAY", "callback surfaceDestroyed notified display=${id(activeDisplay)}") }
                        .onFailure { DiagnosticLog.error("RAW_DISPLAY", "callback surfaceDestroyed notify failed display=${id(activeDisplay)}", it) }
                }
            }
            surfacePublished = false
            surfaceWidth = 0
            surfaceHeight = 0
        }

        private fun publishSurfaceIfReady() {
            val gecko = display ?: run {
                DiagnosticLog.event("RAW_PUBLISH", "skip display=null holderValid=${holder.surface.isValid} session=${DiagnosticLog.sessionLabel(session)}")
                return
            }
            val current = session ?: run {
                DiagnosticLog.event("RAW_PUBLISH", "skip session=null display=${id(gecko)} holderValid=${holder.surface.isValid}")
                return
            }
            val androidSurface = holder.surface
            if (!androidSurface.isValid) {
                DiagnosticLog.event("RAW_PUBLISH", "skip invalid surface display=${id(gecko)} ${DiagnosticLog.sessionLabel(current)}")
                return
            }
            val width = (if (surfaceWidth > 0) surfaceWidth else this.width).coerceAtLeast(1)
            val height = (if (surfaceHeight > 0) surfaceHeight else this.height).coerceAtLeast(1)
            try {
                DiagnosticLog.event(
                    "RAW_PUBLISH",
                    "surfaceChanged begin display=${id(gecko)} surface=${id(androidSurface)} size=${width}x$height " +
                        "surfaceControl=${if (Build.VERSION.SDK_INT >= 29) id(surfaceControl) else "n/a"} ${DiagnosticLog.sessionLabel(current)}"
                )
                val builder = GeckoDisplay.SurfaceInfo.Builder(androidSurface)
                    .newSurfaceProvider(this)
                    .size(width, height)
                if (Build.VERSION.SDK_INT >= 29) builder.surfaceControl(surfaceControl)
                gecko.surfaceChanged(builder.build())
                surfacePublished = true
                publishFailurePosted = false
                DiagnosticLog.event("RAW_PUBLISH", "surfaceChanged success display=${id(gecko)} surface=${id(androidSurface)} size=${width}x$height")
                updateScreenOrigin()
            } catch (error: RuntimeException) {
                // SurfaceHolder callbacks run on the UI thread. Never let a display handoff race kill
                // the whole process; unwind this raw display once and leave the chrome alive so the
                // exact exception is visible/loggable on the physical device.
                if (!publishFailurePosted) {
                    publishFailurePosted = true
                    Log.e(TAG, "Could not publish raw Gecko Surface", error)
                    DiagnosticLog.error(
                        "RAW_PUBLISH",
                        "surfaceChanged failed display=${id(gecko)} surface=${id(androidSurface)} size=${width}x$height ${DiagnosticLog.sessionLabel(current)}",
                        error
                    )
                    post {
                        if (session === current) {
                            DiagnosticLog.event("RAW_PUBLISH", "posted unwind executing ${DiagnosticLog.sessionLabel(current)}")
                            unbind(current)
                            Toast.makeText(context, "Raw Gecko surface failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        } else {
                            DiagnosticLog.event("RAW_PUBLISH", "posted unwind skipped session changed current=${id(current)} actual=${id(session)}")
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
                .onFailure { DiagnosticLog.error("RAW_DISPLAY", "screenOriginChanged failed display=${id(gecko)} x=${screenOrigin[0]} y=${screenOrigin[1]}", it) }
        }

        override fun requestNewSurface() {
            DiagnosticLog.event(
                "RAW_SURFACE",
                "requestNewSurface session=${DiagnosticLog.sessionLabel(session)} display=${id(display)} currentSurface=${id(holder.surface)}"
            )
            post {
                DiagnosticLog.event("RAW_SURFACE", "requestNewSurface toggle begin attached=$isAttachedToWindow visibility=$visibility")
                visibility = View.INVISIBLE
                visibility = View.VISIBLE
                DiagnosticLog.event("RAW_SURFACE", "requestNewSurface toggle complete visibility=$visibility")
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
            DiagnosticLog.event("RAW_INPUT", "onCreateInputConnection session=${DiagnosticLog.sessionLabel(session)}")
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
            DiagnosticLog.event("RAW_WINDOW", "surface focus=$hasWindowFocus ${DiagnosticLog.sessionLabel(session)}")
            Workspace.peek()?.applyPolicy()
        }
    }

    companion object {
        private const val TAG = "BubbleRawGecko"
        private fun id(value: Any?): String = if (value == null) "null" else Integer.toHexString(System.identityHashCode(value))
    }
}
