package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Structural/correctness arena gate. This deliberately makes no latency claim. */
@RunWith(AndroidJUnit4::class)
class DirectGeckoArenaRuntimeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val auto = inst.uiAutomation
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun checkMain(block: () -> Boolean): Boolean { var answer=false;main{answer=block()};return answer }
    private fun await(label:String, timeoutMs:Long=45_000, block:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<end){if(block())return;Thread.sleep(100)}
        save("failure-$label");fail("Timed out: $label")
    }
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).bufferedReader().use{it.readText()}
    private fun save(name:String){auto.takeScreenshot()?.let{b->File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}.resolve("direct-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun inputHost():View?=Workspace.peek()?.selected?.session?.textInput?.view
    private fun tap(x:Float,y:Float){val t=SystemClock.uptimeMillis();val d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);val u=MotionEvent.obtain(t,t+45,MotionEvent.ACTION_UP,x,y,0);d.source=InputDevice.SOURCE_TOUCHSCREEN;u.source=InputDevice.SOURCE_TOUCHSCREEN;try{assertTrue(auto.injectInputEvent(d,true));assertTrue(auto.injectInputEvent(u,true))}finally{d.recycle();u.recycle()}}
    private fun pageTap(y:Float){var x=0f;var py=0f;main{val v=requireNotNull(inputHost());val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width*.5f;py=p[1]+v.height*y};tap(x,py)}
    private fun pageIs(magenta:Boolean):Boolean{
        var x=0;var y=0;var ready=false
        main{inputHost()?.let{v->val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width/8;y=p[1]+v.height*4/5;ready=v.isAttachedToWindow&&v.width>100&&v.height>100}}
        if(!ready)return false
        val b=auto.takeScreenshot()?:return false
        return try{if(x !in 0 until b.width||y !in 0 until b.height)false else {val c=b.getPixel(x,y);if(magenta)Color.red(c)>150&&Color.blue(c)>100&&Color.green(c)<100 else Color.red(c)<100&&Color.green(c)>130&&Color.blue(c)>130}}finally{b.recycle()}
    }
    private fun sameWindow():Boolean=checkMain{val input=inputHost()?:return@checkMain false;val chrome=BubbleService.active?.window?.transitionView?:return@checkMain false;input.rootView===chrome.rootView&&input.windowToken===chrome.windowToken}
    private fun nativeIdle():Boolean{val s=runCatching{NativeAhbBridge.nativeDebugStats()}.getOrElse{return true};return s.getOrElse(0){-1}==0L&&s.getOrElse(5){-1}==0L&&s.getOrElse(4){-1}==0L}

    @Test fun directSurfaceUsesRealFloatingWindowAndCanSwitchToRelayWithoutReload() {
        val server=ServerSocket(0)
        val serving=Thread{
            while(!server.isClosed)try{server.accept().use{s->
                s.soTimeout=5000;val r=s.getInputStream().bufferedReader();r.readLine();while(!r.readLine().isNullOrEmpty()){}
                val html="""<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>ARENA-A</title>
                <style>html,body{margin:0;height:100%;overflow:hidden}canvas{position:fixed;inset:0;width:100%;height:100%}button{position:absolute;left:10%;top:40%;width:80%;height:12%;z-index:2}</style>
                <canvas id=c></canvas><button onclick='flip=!flip;paint();document.title=flip?"ARENA-CYAN":"ARENA-MAGENTA"'>toggle arena color</button>
                <script>const c=document.querySelector('canvas'),g=c.getContext('2d');let flip=false;function paint(){c.width=innerWidth;c.height=innerHeight;g.fillStyle=flip?'rgb(20,200,210)':'rgb(220,30,180)';g.fillRect(0,0,c.width,c.height)}paint();</script>""".toByteArray()
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray());s.getOutputStream().write(html)
            }}catch(_:Exception){if(server.isClosed)break}
        }.apply{isDaemon=true;start()}
        val oldFlags=auto.serviceInfo.flags
        val health=context.getSharedPreferences("notification-health",android.content.Context.MODE_PRIVATE)
        val oldOffer=health.getBoolean("test-offer-v3",false)
        try{
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            health.edit().putBoolean("test-offer-v3",true).commit()
            auto.serviceInfo=auto.serviceInfo.apply{flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java)).use{scenario->
                await("workspace"){checkMain{Workspace.peek()?.ready==true}}
                scenario.onActivity{a->AccessPreferences.get(a).update(AccessPreferences.get(a).options.copy(enabled=false));a.workspace.create("http://127.0.0.1:${server.localPort}/arena")}
                await("page",90_000){checkMain{Workspace.peek()?.selected?.painted==true&&Workspace.peek()?.selected?.title=="ARENA-A"}}
                await("fullscreen-magenta"){pageIs(true)}
                main{RendererArena.transport=RendererArena.Transport.DIRECT_GECKO_SURFACE;PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140}
                scenario.onActivity{a->a.geckoView.postOnAnimation{a.collapse(FloatingMode.CHAT)}}
                await("floating"){checkMain{BubbleService.active?.window?.mode==FloatingMode.CHAT&&Workspace.peek()?.floatingVisible==true}}
                await("direct-magenta"){pageIs(true)}
                assertTrue("direct input shares production window",sameWindow())
                await("direct-no-relay"){nativeIdle()}
                save("direct-magenta")
                pageTap(.46f)
                await("direct-click"){checkMain{Workspace.peek()?.selected?.title=="ARENA-CYAN"}&&pageIs(false)}

                main{BubbleService.active!!.window!!.setRendererTransportForArena(RendererArena.Transport.RELAY_LATEST_BP)}
                await("relay-active"){runCatching{NativeAhbBridge.nativeDebugStats()[0]>0&&NativeAhbBridge.nativeDebugStats()[5]>0}.getOrDefault(false)}
                await("relay-same-session-cyan"){checkMain{Workspace.peek()?.selected?.title=="ARENA-CYAN"}&&pageIs(false)}
                assertTrue("relay still shares production window",sameWindow())
                pageTap(.46f)
                await("relay-click"){checkMain{Workspace.peek()?.selected?.title=="ARENA-MAGENTA"}&&pageIs(true)}

                main{BubbleService.active!!.window!!.setRendererTransportForArena(RendererArena.Transport.DIRECT_GECKO_SURFACE)}
                await("direct-return-idle"){nativeIdle()}
                await("direct-return-same-session"){checkMain{Workspace.peek()?.selected?.title=="ARENA-MAGENTA"}&&pageIs(true)}
                assertTrue(sameWindow())
                save("direct-return")
                File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}.resolve("direct-arena.txt").writeText(
                    "PASS: same GeckoSession/page state survived DIRECT -> relay_latest_bp -> DIRECT in the actual floating window; direct arm had no active Bubble ImageReader relay. Correctness evidence, not a latency score.\n")
            }
        }finally{
            main{RendererArena.transport=RendererArena.Transport.RELAY_LATEST_BP;PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140}
            context.stopService(Intent(context,BubbleService::class.java));server.close();serving.join(1000)
            health.edit().putBoolean("test-offer-v3",oldOffer).commit();auto.serviceInfo=auto.serviceInfo.apply{flags=oldFlags}
        }
    }
}
