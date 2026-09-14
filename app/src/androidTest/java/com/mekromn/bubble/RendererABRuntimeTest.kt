package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Same-process/same-session renderer A/B proof.
 * The input policy is pinned to Build 140 for BOTH arms; only transport changes.
 */
@RunWith(AndroidJUnit4::class)
class RendererABRuntimeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val auto = inst.uiAutomation

    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun checkMain(block: () -> Boolean): Boolean { var r=false; main { r=block() }; return r }
    private fun await(label: String, timeoutMs: Long = 60_000, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; Thread.sleep(100) }
        save("failure-$label")
        fail("Timed out: $label")
    }
    private fun shell(s: String) = ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).bufferedReader().use { it.readText() }
    private fun evidence() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun save(name: String) { auto.takeScreenshot()?.let { b -> evidence().resolve("renderer-ab-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() } }
    private fun stats(): LongArray = NativeAhbBridge.nativeDebugStats()
    private fun relayFullyIdle(): Boolean = stats().let { it[0]==0L && it[1]==0L && it[4]==0L && it[5]==0L }
    private fun fullscreenSourceGone(): Boolean = checkMain { Workspace.peek()?.host?.get()?.hasWindowFocus()!=true }

    private fun pageIs(magenta: Boolean): Boolean {
        var x=0; var y=0; var ready=false
        main { Workspace.peek()?.selected?.session?.textInput?.view?.let { v ->
            val p=IntArray(2); v.getLocationOnScreen(p)
            x=p[0]+v.width/8; y=p[1]+v.height*4/5
            ready=v.isAttachedToWindow && v.width>100 && v.height>100
        } }
        if (!ready) return false
        val b=auto.takeScreenshot() ?: return false
        return try {
            if (x !in 0 until b.width || y !in 0 until b.height) false else {
                val c=b.getPixel(x,y)
                if (magenta) Color.red(c)>150 && Color.blue(c)>100 && Color.green(c)<100
                else Color.red(c)<100 && Color.green(c)>130 && Color.blue(c)>130
            }
        } finally { b.recycle() }
    }

    private fun tap(x: Float, y: Float) {
        val t=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0)
        val up=MotionEvent.obtain(t,t+45,MotionEvent.ACTION_UP,x,y,0)
        down.source=InputDevice.SOURCE_TOUCHSCREEN; up.source=InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(auto.injectInputEvent(down,true)); assertTrue(auto.injectInputEvent(up,true)) }
        finally { down.recycle(); up.recycle() }
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

    private fun floating(transport: RendererArena.Transport): Boolean = checkMain {
        val w=BubbleService.active?.window ?: return@checkMain false
        val host=Workspace.peek()?.selected?.session?.textInput?.view
        w.mode==FloatingMode.CHAT && Workspace.peek()?.floatingVisible==true &&
            w.pageHost?.transport==transport && host is LiveGeckoView &&
            host.isAttachedToWindow && host.session===Workspace.peek()?.selected?.session
    }

    @Test fun relay140AndDirect143SwitchInPlaceWithOneSessionAndFixedInputPolicy() {
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
            } catch (_: Exception) { if(server.isClosed) break }
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
                var original: org.mozilla.geckoview.GeckoSession?=null
                scenario.onActivity { a ->
                    AccessPreferences.get(a).update(AccessPreferences.get(a).options.copy(enabled=false))
                    a.workspace.create("http://127.0.0.1:${server.localPort}/ab")
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
                assertTrue(checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140, PageTouchDispatch.arm)
                val directInitialSubmitted=stats()[2]

                pageTap()
                await("direct-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-CYAN" } && pageIs(false) }
                Thread.sleep(500)
                assertEquals("direct arm must not submit through Bubble relay",directInitialSubmitted,stats()[2])
                save("direct-first")

                main {
                    PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
                    requireNotNull(BubbleService.active?.window).setRendererTransportForArena(RendererArena.Transport.RELAY_LATEST_BP)
                }
                await("relay-live",90_000) { floating(RendererArena.Transport.RELAY_LATEST_BP) && pageIs(false) && stats()[0]>0L && stats()[5]>0L }
                assertTrue("same GeckoSession after direct-to-relay switch",checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140, PageTouchDispatch.arm)
                val relayBeforeClick=stats()[2]
                pageTap()
                await("relay-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-MAGENTA" } && pageIs(true) }
                await("relay-submitted-new-frame") { stats()[2] > relayBeforeClick }
                val relayAfterClick=stats().copyOf()
                save("relay")

                main {
                    PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140
                    requireNotNull(BubbleService.active?.window).setRendererTransportForArena(RendererArena.Transport.DIRECT_GECKO_SURFACE)
                }
                await("direct-return",90_000) { floating(RendererArena.Transport.DIRECT_GECKO_SURFACE) && pageIs(true) }
                await("relay-retired",30_000) { relayFullyIdle() }
                assertTrue("same GeckoSession after relay-to-direct switch",checkMain { Workspace.peek()?.selected?.session===original })
                assertEquals(PageTouchDispatch.Arm.UNBUFFERED_140, PageTouchDispatch.arm)
                val submittedAfterRetire=stats()[2]
                pageTap()
                await("direct-second-click",30_000) { checkMain { Workspace.peek()?.selected?.title=="AB-CYAN" } && pageIs(false) }
                Thread.sleep(700)
                assertEquals("relay submissions must stop after returning to direct",submittedAfterRetire,stats()[2])
                save("direct-return")

                evidence().resolve("renderer-ab.txt").writeText(
                    "PASS: one process/profile/session; fixed UNBUFFERED_140 input; DIRECT->RELAY_LATEST_BP->DIRECT in the real floating window; relay submitted additional frames only in relay arm and became fully idle after direct return. relayAfterClick=${relayAfterClick.joinToString()} final=${stats().joinToString()}\n"
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
