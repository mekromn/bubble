package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.view.ViewParent
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.mozilla.geckoview.GeckoPreferenceController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual browser/session/overlay correctness; no benchmark scores or authenticated pages. */
@RunWith(AndroidJUnit4::class)
class RelayLatestBpRuntimeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val auto = inst.uiAutomation
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun checkMain(block: () -> Boolean): Boolean { var answer=false; main { answer=block() }; return answer }
    private fun await(label: String, timeoutMs: Long = 30_000, block: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<end) { if(block())return;Thread.sleep(100) }
        save("failure-$label");fail("Timed out: $label")
    }
    private fun shell(s: String)=ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).bufferedReader().use { it.readText() }
    private fun save(name: String) {
        auto.takeScreenshot()?.let { b ->
            File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}.resolve("relay-bp-$name.png")
                .outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
    }
    private fun inputHost(): View? = Workspace.peek()?.selected?.session?.textInput?.view
    private fun tap(x: Float,y: Float) {
        val t=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0)
        val up=MotionEvent.obtain(t,t+45,MotionEvent.ACTION_UP,x,y,0)
        down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(auto.injectInputEvent(down,true));assertTrue(auto.injectInputEvent(up,true)) }
        finally {down.recycle();up.recycle()}
    }
    private fun pageTap(y: Float) {
        var x=0f;var top=0f
        main { val v=requireNotNull(inputHost());val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width*.5f;top=p[1]+v.height*y }
        tap(x,top)
    }
    private fun pageHasColor(red: Boolean): Boolean {
        var x=0;var y=0;var ready=false
        main { inputHost()?.let{v->val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width/8;y=p[1]+v.height*4/5;ready=v.isAttachedToWindow&&v.width>100&&v.height>100} }
        if(!ready)return false
        val b=auto.takeScreenshot()?:return false
        try {if(x !in 0 until b.width || y !in 0 until b.height)return false
            val c=b.getPixel(x,y)
            return if(red) Color.red(c)>150&&Color.blue(c)>100&&Color.green(c)<100
              else Color.red(c)<100&&Color.green(c)>130&&Color.blue(c)>130
        }finally{b.recycle()}
    }
    private fun descriptionBounds(description: String): Rect {
        var found: Rect?=null
        fun walk(node:AccessibilityNodeInfo?) {
            if(node==null||found!=null)return
            if(node.isVisibleToUser&&node.contentDescription==description){found=Rect().also{node.getBoundsInScreen(it)};return}
            for(i in 0 until node.childCount)walk(node.getChild(i))
        }
        await(description){found=null;auto.windows.forEach{walk(it.root)};found!=null}
        return requireNotNull(found)
    }
    private fun tapDescription(description: String) {
        val r=descriptionBounds(description);tap(r.exactCenterX(),r.exactCenterY())
    }
    private fun drag(x: Float,y: Float,dx: Float,dy: Float) {
        val downTime=SystemClock.uptimeMillis()
        fun send(action:Int,px:Float,py:Float) {
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,px,py,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(auto.injectInputEvent(event,true)) } finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,x,y)
        repeat(12) { i -> Thread.sleep(16);val t=(i+1)/12f;send(MotionEvent.ACTION_MOVE,x+dx*t,y+dy*t) }
        send(MotionEvent.ACTION_UP,x+dx,y+dy)
    }
    private fun assertSharedHost(label:String) {
        main {
            val input=requireNotNull(inputHost())
            val chrome=requireNotNull(BubbleService.active?.window?.transitionView)
            assertSame("$label same-window root",chrome.rootView,input.rootView)
            assertEquals("$label same-window token",chrome.windowToken,input.windowToken)
            assertTrue("Accessibility parent is a ViewParent",input.parent is ViewParent)
            assertSame(input.parent,Workspace.peek()!!.selected!!.session!!.accessibility.view)
        }
        val windows=shell("dumpsys window windows")
        assertFalse("No standalone page WindowState",windows.contains("Bubble ANativeWindow AHardwareBuffer page"))
        File(context.getExternalFilesDir(null),"evidence").apply { mkdirs() }.resolve("same-window-$label.txt").writeText(
            "PASS: input host and chrome share ViewRoot and window token; real accessibility parent. No standalone page window.\n"+windows)
    }
    private fun sampledArgb(fx:Float,fy:Float): Int {
        val point=floatArrayOf(0f,0f)
        main { val v=requireNotNull(inputHost());point[0]=v.width*fx;point[1]=v.height*fy
            val matrix=Matrix();v.transformMatrixToGlobal(matrix);matrix.mapPoints(point) }
        val b=requireNotNull(auto.takeScreenshot())
        return try { b.getPixel(point[0].toInt().coerceIn(0,b.width-1),point[1].toInt().coerceIn(0,b.height-1)) }
        finally {b.recycle()}
    }
    private fun geometryAndNativeControlChecks() {
        // Only the UI/native layer hierarchy is transformed; the engine still
        // renders at the exact unscaled View dimensions. Check actual pixels.
        main { BubbleService.active!!.window!!.transitionView.apply {
            scaleX=.92f;scaleY=.92f;translationX=5f;translationY=7f;invalidate()
        } }
        await("geometry-scaled-page") { val c=sampledArgb(.1f,.8f);Color.red(c)>150&&Color.blue(c)>100&&Color.green(c)<100 }
        save("geometry-scaled")
        main { BubbleService.active!!.window!!.transitionView.apply { alpha=0f;invalidate() } }
        await("geometry-alpha-hidden") { val c=sampledArgb(.1f,.8f);!(Color.red(c)>150&&Color.blue(c)>100&&Color.green(c)<100) }
        main { BubbleService.active!!.window!!.transitionView.apply {
            alpha=1f;scaleX=1f;scaleY=1f;translationX=0f;translationY=0f;invalidate()
        } }
        await("geometry-restored") { pageHasColor(true) }
        // A native control covering the page must be visible AND clickable.
        // This catches a superficially working positive-Z page that hides chrome.
        var overlay: View?=null;var pressed=false
        main {
            val input=requireNotNull(inputHost());val parent=input.parent as FrameLayout
            overlay=View(context).apply { setBackgroundColor(Color.GREEN);isClickable=true;setOnClickListener{pressed=true} }
            parent.addView(overlay,FrameLayout.LayoutParams(input.width/2,input.height/6).apply{
                leftMargin=input.width/4;topMargin=input.height*3/4 })
        }
        await("native-control-over-page") { val c=sampledArgb(.5f,.80f);Color.green(c)>200&&Color.red(c)<70&&Color.blue(c)<70 }
        pageTap(.80f);await("native-control-click") { checkMain { pressed } };save("native-control-over-page")
        main { overlay?.let { (it.parent as ViewGroup).removeView(it) } }
        await("native-control-removed") { pageHasColor(true) }
    }
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun verifyHardwarePreferences(config: File) {
        val expected = Regex("(?m)^  ([a-z][a-z0-9.-]+): (true|false)$")
            .findAll(config.readText()).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
        assertEquals("All intended startup preferences", 13, expected.size)
        val actual = AtomicReference<Map<String, Any?>?>(null)
        val failure = AtomicReference<Throwable?>(null)
        main {
            GeckoPreferenceController.getGeckoPrefs(expected.keys.toList()).accept(
                { values -> if (values == null) failure.set(IllegalStateException("Null preference result")) else actual.set(values.associate { it.pref to it.value }) },
                { error -> failure.set(error) }
            )
        }
        await("engine-preference-readback") { actual.get() != null || failure.get() != null }
        failure.get()?.let { throw AssertionError("Engine preference readback failed", it) }
        val observed = requireNotNull(actual.get())
        val evidence = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        evidence.resolve("hardware-preferences.txt").writeText(
            "Requested settings vs Gecko readback; NOT proof every operation used physical GPU.\n" +
                expected.entries.joinToString("\n") { (name, value) -> "$name expected=$value actual=${observed[name]}" }
        )
        expected.forEach { (name, value) -> assertEquals(name, value, observed[name]) }
    }

    @Test fun actualBubblePixelsInputTabSwapResizeAndFullscreenReturn() {
        val server=ServerSocket(0)
        val serving=Thread {
            while(!server.isClosed)try{server.accept().use { s ->
                s.soTimeout=5000;val reader=s.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()){}
                val id=if(request.contains("/two"))"B" else "A"
                val color=if(id=="A")"rgb(220,30,180)" else "rgb(20,200,210)"
                val scrollStyle=if(id=="B")"html,body{overflow:auto;height:auto}body{min-height:300vh}" else ""
                val scrollScript=if(id=="B")"addEventListener('scroll',()=>{document.title='SCROLLED-B-'+Math.round(scrollY)})" else ""
                val html=(if(request.contains("/input")) PageTouchChecks.HTML else """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>RELAY-$id</title>
                <style>html,body{margin:0;height:100%;overflow:hidden}canvas{position:fixed;inset:0;width:100%;height:100%}button,input{position:absolute;left:10%;width:80%;height:10%;z-index:2}button{top:40%}input{top:55%}$scrollStyle</style>
                <canvas id="c"></canvas><button onclick="document.title='CLICKED-$id'; flipped=!flipped;paint()">Relay button $id</button><input onfocus="document.title='FOCUSED-$id'" oninput="document.title='TYPED-'+this.value" aria-label="Relay composer">
                <script>const c=document.querySelector('canvas'),g=c.getContext('2d');let flipped=false;function paint(){c.width=innerWidth;c.height=innerHeight;g.fillStyle=flipped?'rgb(20,200,210)':'$color';g.fillRect(0,0,c.width,c.height);g.fillStyle='yellow';g.fillRect(8,8,32,32)}addEventListener('resize',paint);paint();$scrollScript</script>""").toByteArray()
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray());s.getOutputStream().write(html)
            }}catch(_:Exception){if(server.isClosed)break}
        }.apply{isDaemon=true;start()}
        val oldFlags=auto.serviceInfo.flags
        val health=context.getSharedPreferences("notification-health",android.content.Context.MODE_PRIVATE)
        val previouslyOffered=health.getBoolean("test-offer-v3",false)
        try{
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            // This is not a notification-onboarding test. The first attempt's screenshot
            // showed its unrelated modal covering the page before the relay was reached.
            assertTrue(health.edit().putBoolean("test-offer-v3",true).commit())
            auto.serviceInfo=auto.serviceInfo.apply{flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java)).use { scenario ->
                await("workspace"){checkMain{Workspace.peek()?.ready==true}}
                var first="";var second=""
                scenario.onActivity{a->AccessPreferences.get(a).update(AccessPreferences.get(a).options.copy(enabled=false));first=a.workspace.create("http://127.0.0.1:${server.localPort}/one").id}
                // Cold multi-process startup under software-emulated graphics is outside
                // the tested relay operation. Keep the paint/title and screen-pixel gates.
                await("first-page",90_000){checkMain{Workspace.peek()?.selected?.painted==true&&Workspace.peek()?.selected?.title=="RELAY-A"}}
                await("fullscreen-red"){pageHasColor(true)};save("fullscreen-before")
                assertTrue("Fullscreen hardware window",checkMain { inputHost()?.isHardwareAccelerated == true })
                val hardwareConfig=File(context.noBackupFilesDir,"gecko-hardware.yaml")
                assertTrue("Private startup policy exists",hardwareConfig.isFile)
                assertTrue(hardwareConfig.readText().contains("gfx.webrender.all: true"))
                verifyHardwarePreferences(hardwareConfig)
                PageTouchChecks(inst).run("fullscreen", "http://127.0.0.1:${server.localPort}/input?fullscreen", "http://127.0.0.1:${server.localPort}/one")
                await("fullscreen-after-input"){pageHasColor(true)}
                scenario.onActivity{a->a.geckoView.postOnAnimation{a.collapse(FloatingMode.CHAT)}}
                await("floating"){checkMain{BubbleService.active?.window?.mode==FloatingMode.CHAT&&Workspace.peek()?.floatingVisible==true}}
                await("floating-red"){pageHasColor(true)};save("floating-A")
                assertSharedHost("initial")
                // Cancellation is not a second navigation. Exercise the actual
                // root key handler deterministically before the real IME test.
                main {
                    val w=requireNotNull(BubbleService.active?.window)
                    val t=SystemClock.uptimeMillis()
                    w.transitionView.dispatchKeyEvent(android.view.KeyEvent(t,t,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK,0))
                    val up=android.view.KeyEvent(t,t+1,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK,0)
                    w.transitionView.dispatchKeyEvent(android.view.KeyEvent.changeFlags(up,android.view.KeyEvent.FLAG_CANCELED))
                    assertEquals(FloatingMode.CHAT,w.mode)
                    assertFalse("Canceled Back must not start collapse",w.isTransitioning)
                }
                geometryAndNativeControlChecks()
                PageTouchChecks(inst).run("floating", "http://127.0.0.1:${server.localPort}/input?floating", "http://127.0.0.1:${server.localPort}/one")
                await("floating-after-input"){pageHasColor(true)}
                var beforeMove: WindowBox?=null
                main { beforeMove=BubbleService.active!!.window!!.box }
                val handle=descriptionBounds("Drag floating window")
                drag(handle.exactCenterX(),handle.exactCenterY(),-24f,-30f)
                await("dragged-page") {checkMain{BubbleService.active!!.window!!.box!=beforeMove}&&pageHasColor(true)}
                assertSharedHost("dragged");save("dragged-page")
                assertTrue("Floating hardware window",checkMain { inputHost()?.isHardwareAccelerated == true })
                assertEquals(6L,NativeAhbBridge.nativeDebugStats()[7]);assertEquals(4L,NativeAhbBridge.nativeDebugStats()[8]);assertEquals(1L,NativeAhbBridge.nativeDebugStats()[9])
                pageTap(.45f);await("click"){checkMain{Workspace.peek()?.selected?.title=="CLICKED-A"}}
                await("idle-resume-first-cyan"){pageHasColor(false)}
                // Returned buffers while idle must not be required to keep the
                // next frame flowing. Each click visibly changes page pixels.
                // These pauses exercise idle/restart; no latency claim is made.
                repeat(7){ i ->
                    Thread.sleep(250)
                    pageTap(.45f)
                    await("idle-resume-$i"){pageHasColor(i % 2 == 0)}
                }
                save("idle-resume-final-red")
                File(context.getExternalFilesDir(null),"evidence/idle-resume.txt").writeText(
                    "PASS: eight actual page-click/color changes, seven following idle pauses. Correctness only; not timed touch latency.\n")
                pageTap(.60f)
                // Shell input injection is not synchronized to DOM focus. Do
                // not send the first character before the actual field accepts
                // focus; still require the complete unchanged input string.
                await("composer-focused"){checkMain{Workspace.peek()?.selected?.title=="FOCUSED-A"&&inputHost()?.hasFocus()==true}}
                await("ime-visible") { checkMain {inputHost()?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true} }
                save("ime-visible")
                shell("input text relay")
                await("typing"){checkMain{Workspace.peek()?.selected?.title=="TYPED-relay"}}
                shell("input keyevent KEYCODE_BACK")
                await("ime-hidden-not-collapsed") { checkMain {
                    BubbleService.active?.window?.mode==FloatingMode.CHAT &&
                    inputHost()?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==false
                } }
                save("ime-hidden-chat-retained")
                // A separate second Back, after IME is hidden, still collapses.
                // Do not fix the first-Back bug by disabling navigation.
                shell("input keyevent KEYCODE_BACK")
                await("second-back-collapses"){checkMain{BubbleService.active?.window?.mode==FloatingMode.BUBBLE}}
                main { BubbleService.active!!.window!!.openChat(first) }
                await("back-reopen-same-page"){pageHasColor(true)}
                assertSharedHost("back-reopened")
                File(context.getExternalFilesDir(null),"evidence/back-ime.txt").writeText(
                    "PASS: canceled key-up does not dismiss; real Back hides IME without collapse; separate second Back collapses; retained page reopens. Correctness, not latency.\n")
                main{second=Workspace.peek()!!.create("http://127.0.0.1:${server.localPort}/two").id;BubbleService.active!!.window!!.openChat(second)}
                await("cold-tab-cyan"){pageHasColor(false)};save("floating-cold-B")
                repeat(6){i->val red=i%2==0;main{BubbleService.active!!.window!!.openChat(if(red)first else second)};await("tab-$i"){pageHasColor(red)}}
                var beforeWidth=0
                main { beforeWidth=BubbleService.active!!.window!!.box.width }
                val resize=descriptionBounds("Resize floating chat")
                drag(resize.exactCenterX(),resize.exactCenterY(),-40f,0f)
                await("resize-layout") {checkMain{BubbleService.active!!.window!!.box.width<beforeWidth}}
                assertSharedHost("resized")
                await("resized-cyan"){pageHasColor(false)};save("resized")
                // Real touch -> Gecko APZ -> scrollable document; not a JS scroll timer.
                var sx=0f;var sy=0f;var distance=0f
                main { val v=requireNotNull(inputHost());val p=IntArray(2);v.getLocationOnScreen(p)
                    sx=p[0]+v.width*.75f;sy=p[1]+v.height*.90f;distance=-v.height*.70f }
                drag(sx,sy,0f,distance)
                await("scrolled-B") {checkMain{Workspace.peek()?.selected?.title?.let{it.startsWith("SCROLLED-B-")&&it!="SCROLLED-B-0"}==true}}
                save("scrolled-B")
                tapDescription("Choose another conversation")
                await("chooser") {checkMain{BubbleService.active?.window?.mode==FloatingMode.CHOOSER}}
                save("chooser-without-page")
                main { BubbleService.active!!.window!!.openChat(second) }
                await("chooser-return-cyan") {pageHasColor(false)}
                assertSharedHost("chooser-return")
                await("old-generations-reclaimed"){val a=NativeAhbBridge.nativeDebugStats();a[0]==1L&&a[1]==0L&&a[5]==1L}
                assertEquals("No native relay errors",0L,NativeAhbBridge.nativeDebugStats()[6])
                tapDescription("Open fullscreen")
                await("fullscreen-return"){checkMain{Workspace.peek()?.visible==true&&Workspace.peek()?.selectedId==second}}
                await("fullscreen-cyan"){pageHasColor(false)};save("fullscreen-return")
                await("all-leases-reclaimed"){val a=NativeAhbBridge.nativeDebugStats();a[0]==0L&&a[1]==0L&&a[4]==0L&&a[5]==0L}
                val stats=NativeAhbBridge.nativeDebugStats();assertTrue(stats[2]>0);assertEquals(stats[2],stats[3])
            }
        }finally{
            context.stopService(Intent(context,BubbleService::class.java));server.close();serving.join(1000)
            health.edit().putBoolean("test-offer-v3",previouslyOffered).commit()
            auto.serviceInfo=auto.serviceInfo.apply{flags=oldFlags}
        }
    }
}
