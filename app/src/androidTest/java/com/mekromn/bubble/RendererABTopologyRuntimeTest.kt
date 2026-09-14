package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final renderer A/B topology proof. Direct Gecko uses GeckoView itself as the input view;
 * relay_latest_bp deliberately binds Gecko input to its attached NativeBufferHost SurfaceView
 * while its RawSessionBridge remains detached bookkeeping. Both arms keep Build 140 input policy.
 */
@RunWith(AndroidJUnit4::class)
class RendererABTopologyRuntimeTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val auto=inst.uiAutomation

    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun checkMain(block:()->Boolean):Boolean { var r=false; main { r=block() }; return r }
    private fun await(label:String, timeoutMs:Long=60_000, block:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<end) { if(block()) return; Thread.sleep(100) }
        save("failure-$label")
        val s=stats()
        val state=runCatching { mainState() }.getOrDefault("unavailable")
        fail("Timed out: $label; state=$state; stats=${s.joinToString()}")
    }
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).bufferedReader().use { it.readText() }
    private fun evidence()=File(context.getExternalFilesDir(null),"evidence").apply { mkdirs() }
    private fun save(name:String) { auto.takeScreenshot()?.let { b -> evidence().resolve("renderer-ab-final-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() } }
    private fun stats():LongArray=NativeAhbBridge.nativeDebugStats()
    private fun relayFullyIdle():Boolean=stats().let { it[0]==0L && it[1]==0L && it[4]==0L && it[5]==0L }
    private fun fullscreenSourceGone():Boolean=checkMain { Workspace.peek()?.host?.get()?.hasWindowFocus()!=true }

    private fun mainState():String {
        var value=""
        main {
            val w=BubbleService.active?.window
            val tab=Workspace.peek()?.selected
            val session=tab?.session
            val input=session?.textInput?.view
            val host=w?.pageHost
            value="mode=${w?.mode} visible=${Workspace.peek()?.floatingVisible} requested=${RendererArena.transport} active=${host?.transport} hostView=${host?.view?.javaClass?.simpleName} hostSession=${host?.view?.session===session} input=${input?.javaClass?.simpleName} inputAttached=${input?.isAttachedToWindow} inputIsHost=${input===host?.view} rootWindowFocus=${w?.transitionView?.hasWindowFocus()} inputWindowFocus=${input?.hasWindowFocus()} inputShown=${input?.isShown} inputVisibility=${input?.windowVisibility}"
        }
        return value
    }

    private fun floating(transport:RendererArena.Transport):Boolean=checkMain {
        val w=BubbleService.active?.window ?: return@checkMain false
        val tab=Workspace.peek()?.selected ?: return@checkMain false
        val session=tab.session ?: return@checkMain false
        val pageHost=w.pageHost ?: return@checkMain false
        val input=session.textInput.view ?: return@checkMain false
        if(w.mode!=FloatingMode.CHAT || Workspace.peek()?.floatingVisible!=true ||
            pageHost.transport!=transport || pageHost.view.session!==session || !input.isAttachedToWindow) return@checkMain false
        when(transport) {
            RendererArena.Transport.DIRECT_GECKO_SURFACE -> input===pageHost.view && input is LiveGeckoView && input.session===session
            RendererArena.Transport.RELAY_LATEST_BP -> input!==pageHost.view && input is SurfaceView
        }
    }

    private fun focusedForInput():Boolean=checkMain {
        val w=BubbleService.active?.window ?: return@checkMain false
        val input=Workspace.peek()?.selected?.session?.textInput?.view ?: return@checkMain false
        w.transitionView.hasWindowFocus() && input.hasWindowFocus() && input.isShown && input.windowVisibility==View.VISIBLE
    }

    private fun awaitStableInputFocus(label:String) {
        await("$label-window-focus",30_000) { focusedForInput() }
        Thread.sleep(250)
        assertTrue("$label focus must remain stable before input; ${mainState()}", focusedForInput())
    }

    private fun pageIs(magenta:Boolean):Boolean {
        var x=0; var y=0; var ready=false
        main { Workspace.peek()?.selected?.session?.textInput?.view?.let { v ->
            val p=IntArray(2); v.getLocationOnScreen(p)
            x=p[0]+v.width/8; y=p[1]+v.height*4/5
            ready=v.isAttachedToWindow && v.width>100 && v.height>100
        } }
        if(!ready) return false
        val b=auto.takeScreenshot() ?: return false
        return try {
            if(x !in 0 until b.width || y !in 0 until b.height) false else {
                val c=b.getPixel(x,y)
                if(magenta) Color.red(c)>150 && Color.blue(c)>100 && Color.green(c)<100
                else Color.red(c)<100 && Color.green(c)>130 && Color.blue(c)>130
            }
        } finally { b.recycle() }
    }

    private fun tap(x:Float,y:Float) {
        val t=SystemClock.uptimeMillis()
        val d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0)
        val u=MotionEvent.obtain(t,t+45,MotionEvent.ACTION_UP,x,y,0)
        d.source=InputDevice.SOURCE_TOUCHSCREEN; u.source=InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(auto.injectInputEvent(d,true)); assertTrue(auto.injectInputEvent(u,true)) }
        finally { d.recycle(); u.recycle() }
    }
    private fun pageTap() {
        var x=0f; var y=0f
        main {
            val v=requireNotNull(Workspace.peek()?.selected?.session?.textInput?.view)
            val p=IntArray(2); v.getLocationOnScreen(p)
            x=p[0]+v.width*.5f; y=p[1]+v.height*.46f
        }
        tap(x,y)
    }

    @Test fun sameSessionDirectRelayDirectUsesRealTransportAndFixed140Input() {
        val server=ServerSocket(0)
        val serving=Thread {
            while(!server.isClosed) try {
                server.accept().use { s ->
                    s.soTimeout=5000
                    val r=s.getInputStream().bufferedReader(); r.readLine(); while(!r.readLine().isNullOrEmpty()) {}
                    val html="""<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>AB-MAGENTA</title><style>html,body{margin:0;height:100%;overflow:hidden}canvas{position:fixed;inset:0;width:100%;height:100%}button{position:absolute;left:10%;top:40%;width:80%;height:12%;z-index:2;font-size:22px}</style><canvas id=c></canvas><button onclick='flip=!flip;paint();document.title=flip?"AB-CYAN":"AB-MAGENTA"'>toggle</button><script>const c=document.querySelector('canvas'),g=c.getContext('2d');let flip=false;function paint(){c.width=innerWidth;c.height=innerHeight;g.fillStyle=flip?'rgb(20,200,210)':'rgb(220,30,180)';g.fillRect(0,0,c.width,c.height)}paint()</script>""".toByteArray()
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    s.getOutputStream().write(html)
                }
            } catch(_:Exception) { if(server.isClosed) break }
        }.apply { isDaemon=true; start() }

        val oldFlags=auto.serviceInfo.flags
        val health=context.getSharedPreferences("notification-health",android.content.Context.MODE_PRIVATE)
        val oldOffer=health.getBoolean("test-offer-v3",false)
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            health.edit().putBoolean("test-offer-v3",true).commit()
            auto.serviceInfo=auto.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }

            ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java)).use { scenario ->
                await("workspace") { checkMain { Workspace.peek()?.ready==true } }
                var original:org.mozilla.geckoview.GeckoSession?=null
                scenario.onActivity { a ->
                    AccessPreferences.get(a).update(AccessPreferences.get(a).options.copy(enabled=false))
                    a.workspace.create("http://127.0.0.1:${server.localPort}/ab-final")
                }
                await("fullscreen-first",90_000) { checkMain { Workspace.peek()?.selected?.painted==true && Workspace.peek()?.selected?.title=="AB-MAGENTA" } && pageIs(true) }

                main {
                    original=Workspace.peek()?.selected?.session
                    PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
                    RendererArena.transport=RendererArena.Transport.DIRECT_GECKO_SURFACE
                }
                scenario.onActivity { it.collapse(FloatingMode.CHAT) }
                await("fullscreen-source-hidden",30_000) { fullscreenSourceGone() }
                await("direct-start",90_000) { floating(RendererArena.Transport.DIRECT_GECKO_SURFACE) && pageIs(true) && relayFullyIdle() }
                awaitStableInputFocus("direct-start")
                assertTrue("direct preserves exact GeckoSession",checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140,PageTouchDispatch.arm)
                val directSubmitted=stats()[2]
                pageTap()
                await("direct-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-CYAN" } && pageIs(false) }
                Thread.sleep(500)
                assertEquals("direct must not feed Bubble relay",directSubmitted,stats()[2])
                save("direct-first")

                main {
                    PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
                    requireNotNull(BubbleService.active?.window).setRendererTransportForArena(RendererArena.Transport.RELAY_LATEST_BP)
                }
                await("relay-host",90_000) { floating(RendererArena.Transport.RELAY_LATEST_BP) && pageIs(false) }
                await("relay-native-active",30_000) { stats()[0]>0L && stats()[5]>0L }
                awaitStableInputFocus("relay")
                assertTrue("relay preserves exact GeckoSession",checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140,PageTouchDispatch.arm)
                val relayBefore=stats()[2]
                pageTap()
                await("relay-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-MAGENTA" } && pageIs(true) }
                await("relay-submitted-new-frame") { stats()[2]>relayBefore }
                val relayStats=stats().copyOf()
                save("relay")

                main {
                    PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
                    requireNotNull(BubbleService.active?.window).setRendererTransportForArena(RendererArena.Transport.DIRECT_GECKO_SURFACE)
                }
                await("direct-return",90_000) { floating(RendererArena.Transport.DIRECT_GECKO_SURFACE) && pageIs(true) }
                await("relay-retired",30_000) { relayFullyIdle() }
                awaitStableInputFocus("direct-return")
                assertTrue("direct return preserves exact GeckoSession",checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140,PageTouchDispatch.arm)
                val submittedAfterRetire=stats()[2]
                pageTap()
                await("direct-second-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-CYAN" } && pageIs(false) }
                Thread.sleep(700)
                assertEquals("relay submissions stop after direct return",submittedAfterRetire,stats()[2])
                save("direct-return")

                evidence().resolve("renderer-ab-final.txt").writeText(
                    "PASS: same process/profile/GeckoSession; UNBUFFERED_140 fixed for both arms; stable floating window focus before input; actual direct LiveGeckoView -> relay NativeBufferHost SurfaceView -> direct LiveGeckoView; relay generated new submissions only during relay arm and fully retired after return. relay=${relayStats.joinToString()} final=${stats().joinToString()}\n"
                )
            }
        } finally {
            context.stopService(Intent(context,BubbleService::class.java))
            server.close(); serving.join(1000)
            health.edit().putBoolean("test-offer-v3",oldOffer).commit()
            auto.serviceInfo=auto.serviceInfo.apply { flags=oldFlags }
            PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
            RendererArena.transport=RendererArena.Transport.DIRECT_GECKO_SURFACE
        }
    }
}
