from pathlib import Path


def once(path: str, old: str, new: str) -> None:
    p=Path(path); t=p.read_text()
    if old in t:
        assert t.count(old)==1,(path,t.count(old),old)
        p.write_text(t.replace(old,new,1))
    else:
        assert new in t,(path,'replacement missing')

# Direct host: expose actual page view and one-shot compositor capture.
p='app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt'
once(p,'import android.graphics.Color\n','import android.graphics.Bitmap\nimport android.graphics.Color\n')
once(p,'    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE\n','    override val transport = RendererArena.Transport.DIRECT_GECKO_SURFACE\n    override val pageView: View get() = host\n')
once(p,'    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }\n','''    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }
    override fun capturePagePixels(done: (Bitmap?) -> Unit) = host.capturePagePixels(done)
''')
once(p,'        fun updateScreenOrigin() {','''        fun capturePagePixels(done: (Bitmap?) -> Unit) {
            val gecko = display
            if (gecko == null || !surfacePublished) { done(null); return }
            try {
                gecko.capturePixels().accept({ image -> done(image) }, { _ -> done(null) })
            } catch (_: RuntimeException) { done(null) }
        }

        fun updateScreenOrigin() {''')

# Relay fallback gets the same transition-capture contract, but remains non-default.
p='app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt'
once(p,'import android.graphics.Color\n','import android.graphics.Bitmap\nimport android.graphics.Color\n')
once(p,'    override val transport = RendererArena.Transport.RELAY_LATEST_BP\n','    override val transport = RendererArena.Transport.RELAY_LATEST_BP\n    override val pageView: View get() = host\n')
once(p,'    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }\n','''    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }
    override fun capturePagePixels(done: (Bitmap?) -> Unit) = host.capturePagePixels(done)
''')
once(p,'        fun updateScreenOrigin() {','''        fun capturePagePixels(done: (Bitmap?) -> Unit) {
            val gecko = display
            if (gecko == null || !surfacePublished) { done(null); return }
            try {
                gecko.capturePixels().accept({ image -> done(image) }, { _ -> done(null) })
            } catch (_: RuntimeException) { done(null) }
        }

        fun updateScreenOrigin() {''')

# Floating window: direct is steady state, relay is automatic safety fallback, and
# fullscreen transition must capture before the service releases the direct SurfaceView.
p='app/src/main/java/com/mekromn/bubble/FloatingWindow.kt'
once(p,
'''        val view=gecko
        if(tab.error==null && view!=null) {
            pageContainer?.let { geckoWindow?.show(it) }
            val session=tab.session
            if(session!=null && session.isOpen)workspace.attachSurface(view,session) else if(view.session!=null)workspace.detachSurface(view)
        } else {
''',
'''        if(tab.error==null && geckoWindow!=null) {
            pageContainer?.let { parent ->
                val current=geckoWindow
                if(current!=null && !current.show(parent) && current.transport==RendererArena.Transport.DIRECT_GECKO_SURFACE) {
                    current.destroy()
                    RendererArena.transport=RendererArena.Transport.RELAY_LATEST_BP
                    geckoWindow=RendererArena.createFallback(context)
                    geckoWindow?.show(parent)
                    setPanelBackground(FloatingMode.CHAT)
                }
            }
            val view=gecko
            val session=tab.session
            if(view!=null && session!=null && session.isOpen)workspace.attachSurface(view,session)
            else if(view?.session!=null)workspace.detachSurface(view)
        } else {
''')
once(p,
'''    private fun fullscreen() {
        QuickPanel.dismissFor(root)
        geckoWindow?.hide()
        try { service.startActivity(Intent(service,BrowserActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra(BrowserActivity.EXTRA_TAB,workspace.selectedId)
        }) } catch(_:RuntimeException) { Toast.makeText(context,"Could not open the browser window",Toast.LENGTH_SHORT).show(); render() }
    }
''',
'''    private fun fullscreen() {
        QuickPanel.dismissFor(root)
        val intent=Intent(service,BrowserActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BrowserActivity.EXTRA_TAB,workspace.selectedId)
        }
        try { FullscreenHandoff.launchFromFloating(context,root,geckoWindow,intent) }
        catch(_:RuntimeException) { Toast.makeText(context,"Could not open the browser window",Toast.LENGTH_SHORT).show(); render() }
    }
''')

# Hybrid transition state and reverse snapshot morph.
p='app/src/main/java/com/mekromn/bubble/FullscreenHandoff.kt'
once(p,'    private var shrinkOverlay: FullscreenShrinkOverlay? = null\n    private var shrinkWatchdog: Runnable? = null\n','''    private var shrinkOverlay: FullscreenShrinkOverlay? = null
    private var shrinkWatchdog: Runnable? = null
    private var expandOverlay: FullscreenShrinkOverlay? = null
    private var pendingExpandFrame: MorphFrame? = null
''')

old='''    /**
     * Keep floating -> fullscreen exactly on the accepted pre-matched-morph behavior. Android owns
     * the clip reveal from the whole floating card; Bubble does not insert a screenshot compositor.
     */
    fun launchFromFloating(context: Context, source: View, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_FROM_FLOATING, true)

        var launchSource = source
        var parent = source.parent
        while (parent is View && parent.isLaidOut && parent.width > 0 && parent.height > 0) {
            launchSource = parent
            parent = parent.parent
        }

        val options = if (launchSource.isLaidOut && launchSource.width > 0 && launchSource.height > 0) {
            ActivityOptions.makeClipRevealAnimation(
                launchSource,
                0,
                0,
                launchSource.width,
                launchSource.height
            ).toBundle()
        } else null
        context.startActivity(intent, options)
    }
'''
new='''    /**
     * Floating -> fullscreen hybrid handoff. Normal browsing stays direct-to-SurfaceView; only the
     * brief cross-window geometry animation uses one frozen frame. Capture happens before the
     * floating service can release Gecko's direct Surface.
     */
    fun launchFromFloating(context: Context, source: View, host: FloatingPageHost?, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.putExtra(EXTRA_FROM_FLOATING, true)
        cancelPendingExpandFrame()
        captureFloatingFrame(source, host) { frame ->
            if (frame == null) {
                context.startActivity(intent)
                return@captureFloatingFrame
            }
            pendingExpandFrame = frame
            val overlay = FullscreenShrinkOverlay(
                context.applicationContext,
                frame,
                fullscreenTarget(context),
                Ui.dp(context,26f).toFloat(),
                0f
            )
            expandOverlay?.detach()
            expandOverlay = overlay
            try {
                overlay.attach { context.startActivity(intent) }
            } catch (_: RuntimeException) {
                if (expandOverlay === overlay) expandOverlay = null
                pendingExpandFrame = null
                overlay.detach()
                context.startActivity(intent)
            }
        }
    }
'''
once(p,old,new)

once(p,
'''    /** Compatibility with BrowserActivity left by the rejected matched-morph experiment. */
    fun isEnteringFullscreen(intent: Intent?): Boolean = false
    fun finishIntoFullscreen(activity: Activity, root: View) = Unit
''',
'''    fun isEnteringFullscreen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FLOATING,false)==true && expandOverlay!=null

    fun finishIntoFullscreen(activity: Activity, root: View) {
        val overlay=expandOverlay ?: run { root.alpha=1f; return }
        pendingExpandFrame=null
        fun begin(attempt:Int) {
            if(activity.isFinishing) {
                root.alpha=1f
                if(expandOverlay===overlay)expandOverlay=null
                overlay.detach(); return
            }
            if(!root.isAttachedToWindow || !root.isLaidOut || root.width<=0 || root.height<=0) {
                if(attempt<30)main.postDelayed({begin(attempt+1)},8L)
                else { root.alpha=1f; if(expandOverlay===overlay)expandOverlay=null; overlay.detach() }
                return
            }
            // The direct fullscreen Gecko surface is already attached under the opaque frozen frame.
            // Give it two 120-Hz opportunities before the screenshot starts moving.
            root.alpha=LIVE_DESTINATION_WARM_ALPHA
            root.postOnAnimation { root.postOnAnimation {
                if(expandOverlay!==overlay)return@postOnAnimation
                overlay.morphInto(root,durationMs=300L,crossfadeStart=.72f) {
                    root.alpha=1f
                    if(expandOverlay===overlay)expandOverlay=null
                    overlay.detach()
                    activity.intent?.removeExtra(EXTRA_FROM_FLOATING)
                }
            } }
        }
        begin(0)
    }
''')

once(p,
'''    fun cancelAll() {
        cancelPendingFullscreenFrame()
        clearShrinkWatchdog()
        shrinkOverlay?.detach()
        shrinkOverlay = null
    }
''',
'''    fun cancelAll() {
        cancelPendingFullscreenFrame()
        cancelPendingExpandFrame()
        clearShrinkWatchdog()
        shrinkOverlay?.detach(); shrinkOverlay = null
        expandOverlay?.detach(); expandOverlay = null
    }
''')

insert='''
    private fun captureFloatingFrame(root: View, host: FloatingPageHost?, result: (MorphFrame?) -> Unit) {
        if(!root.isLaidOut || root.width<=0 || root.height<=0 || !root.isAttachedToWindow) { result(null); return }
        val base=drawFallback(root) ?: run { result(null); return }
        val location=IntArray(2); root.getLocationOnScreen(location)
        val box=WindowBox(location[0],location[1],root.width,root.height)
        if(host==null) { result(MorphFrame(base,box)); return }
        val finished=AtomicBoolean(false)
        val timeout=Runnable { if(finished.compareAndSet(false,true)) result(MorphFrame(base,box)) }
        main.postDelayed(timeout,GECKO_CAPTURE_TIMEOUT_MS)
        host.capturePagePixels { pixels ->
            if(!finished.compareAndSet(false,true)) { pixels?.safeRecycle(); return@capturePagePixels }
            main.removeCallbacks(timeout)
            if(pixels!=null && !pixels.isRecycled) {
                try { compositeIntoOwner(host.pageView,root,base,pixels) } catch(_:Throwable) { }
                pixels.safeRecycle()
            }
            result(MorphFrame(base,box))
        }
    }

    private fun fullscreenTarget(context: Context): WindowBox {
        val manager=context.getSystemService(WindowManager::class.java)
        return if(Build.VERSION.SDK_INT>=30) {
            val b=manager.maximumWindowMetrics.bounds
            WindowBox(b.left,b.top,b.width().coerceAtLeast(1),b.height().coerceAtLeast(1))
        } else {
            val p=Point(); @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
            WindowBox(0,0,p.x.coerceAtLeast(1),p.y.coerceAtLeast(1))
        }
    }

    private fun cancelPendingExpandFrame() {
        if(expandOverlay==null) pendingExpandFrame?.bitmap.safeRecycle()
        pendingExpandFrame=null
    }
'''
marker='    private fun cancelPendingFullscreenFrame() {'
t=Path(p).read_text()
if insert not in t:
    assert marker in t
    Path(p).write_text(t.replace(marker,insert+'\n'+marker,1))

# Small local constant; the overlay remains fully opaque above this warm live destination.
t=Path(p).read_text()
if 'LIVE_DESTINATION_WARM_ALPHA' not in t.split('internal object FullscreenHandoff {',1)[1].split('fun',1)[0]:
    t=t.replace('    private const val SHRINK_WATCHDOG_MS = 1400L\n','    private const val SHRINK_WATCHDOG_MS = 1400L\n    private const val LIVE_DESTINATION_WARM_ALPHA = 0.002f\n',1)
    Path(p).write_text(t)

# Production identity: no arena launcher, direct hybrid version 143.
p='app/build.gradle.kts'
once(p,'        versionCode = 142\n        versionName = "0.7.14-renderer-arena"','        versionCode = 143\n        versionName = "0.7.15-direct-hybrid"')

p='app/src/main/AndroidManifest.xml'
t=Path(p).read_text()
start='''        <activity android:name=".RendererArenaActivity" android:exported="true" android:label="Bubble Renderer Arena">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''
if start in t:
    t=t.replace(start,'',1)
elif '<activity android:name=".RendererArenaActivity"' in t:
    # Handle pre-launcher arena manifest shape.
    t=t.replace('        <activity android:name=".RendererArenaActivity" android:exported="false" android:label="Renderer Arena" />\n','',1)
Path(p).write_text(t)

print('hybrid143 source transform applied')
