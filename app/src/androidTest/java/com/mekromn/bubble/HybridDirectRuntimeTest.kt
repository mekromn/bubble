package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production hybrid correctness: direct GeckoView SurfaceView steady state, frozen frame only during morphs. */
@RunWith(AndroidJUnit4::class)
class HybridDirectRuntimeTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val auto=inst.uiAutomation
    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun checkMain(block:()->Boolean):Boolean{var result=false;main{result=block()};return result}
    private fun await(label:String,timeoutMs:Long=60_000,block:()->Boolean){val end=SystemClock.elapsedRealtime()+timeoutMs;while(SystemClock.elapsedRealtime()<end){if(block())return;Thread.sleep(100)};save("failure-$label");fail("Timed out: $label")}
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).bufferedReader().use{it.readText()}
    private fun save(name:String){auto.takeScreenshot()?.let{b->File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}.resolve("hybrid-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun inputHost()=Workspace.peek()?.selected?.session?.textInput?.view
    private fun relayIdle():Boolean=runCatching{NativeAhbBridge.nativeDebugStats().let{it[0]==0L&&it[4]==0L&&it[5]==0L}}.getOrDefault(true)
    private fun pageIs(magenta:Boolean):Boolean{var x=0;var y=0;var ready=false;main{inputHost()?.let{v->val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width/8;y=p[1]+v.height*4/5;ready=v.isAttachedToWindow&&v.width>100&&v.height>100}};if(!ready)return false;val b=auto.takeScreenshot()?:return false;return try{if(x !in 0 until b.width||y !in 0 until b.height)false else{val c=b.getPixel(x,y);if(magenta)Color.red(c)>150&&Color.blue(c)>100&&Color.green(c)<100 else Color.red(c)<100&&Color.green(c)>130&&Color.blue(c)>130}}finally{b.recycle()}}
    private fun tap(x:Float,y:Float){val t=SystemClock.uptimeMillis();val d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);val u=MotionEvent.obtain(t,t+45,MotionEvent.ACTION_UP,x,y,0);d.source=InputDevice.SOURCE_TOUCHSCREEN;u.source=InputDevice.SOURCE_TOUCHSCREEN;try{assertTrue(auto.injectInputEvent(d,true));assertTrue(auto.injectInputEvent(u,true))}finally{d.recycle();u.recycle()}}
    private fun pageTap(){var x=0f;var y=0f;main{val v=requireNotNull(inputHost());val p=IntArray(2);v.getLocationOnScreen(p);x=p[0]+v.width*.5f;y=p[1]+v.height*.46f};tap(x,y)}
    private fun descriptionBounds(description:String):Rect{var found:Rect?=null;fun walk(node:AccessibilityNodeInfo?){if(node==null||found!=null)return;if(node.isVisibleToUser&&node.contentDescription==description){found=Rect().also{node.getBoundsInScreen(it)};return};for(i in 0 until node.childCount)walk(node.getChild(i))};await(description){found=null;auto.windows.forEach{walk(it.root)};found!=null};return requireNotNull(found)}
    private fun tapDescription(description:String){val r=descriptionBounds(description);tap(r.exactCenterX(),r.exactCenterY())}
    private fun directFloating():Boolean=checkMain{
        val w=BubbleService.active?.window?:return@checkMain false
        val host=inputHost()
        w.mode==FloatingMode.CHAT && Workspace.peek()?.floatingVisible==true &&
            w.pageHost?.transport==RendererArena.Transport.DIRECT_GECKO_SURFACE &&
            host is LiveGeckoView && host.isAttachedToWindow && host.session===Workspace.peek()?.selected?.session
    }

    @Test fun directBrowsingSurvivesBothSnapshotWindowMorphsWithoutRelaySteadyState(){
        val server=ServerSocket(0)
        val serving=Thread{while(!server.isClosed)try{server.accept().use{s->s.soTimeout=5000;val r=s.getInputStream().bufferedReader();r.readLine();while(!r.readLine().isNullOrEmpty()){};val html="""<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>HYBRID-MAGENTA</title><style>html,body{margin:0;height:100%;overflow:hidden}canvas{position:fixed;inset:0;width:100%;height:100%}button{position:absolute;left:10%;top:40%;width:80%;height:12%;z-index:2}</style><canvas id=c></canvas><button onclick='flip=!flip;paint();document.title=flip?"HYBRID-CYAN":"HYBRID-MAGENTA"'>toggle</button><script>const c=document.querySelector('canvas'),g=c.getContext('2d');let flip=false;function paint(){c.width=innerWidth;c.height=innerHeight;g.fillStyle=flip?'rgb(20,200,210)':'rgb(220,30,180)';g.fillRect(0,0,c.width,c.height)}paint()</script>""".toByteArray();s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray());s.getOutputStream().write(html)}}catch(_:Exception){if(server.isClosed)break}}.apply{isDaemon=true;start()}
        val oldFlags=auto.serviceInfo.flags
        val health=context.getSharedPreferences("notification-health",android.content.Context.MODE_PRIVATE)
        val oldOffer=health.getBoolean("test-offer-v3",false)
        try{
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow");shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS");health.edit().putBoolean("test-offer-v3",true).commit();auto.serviceInfo=auto.serviceInfo.apply{flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java)).use{scenario->
                await("workspace"){checkMain{Workspace.peek()?.ready==true}}
                var original:org.mozilla.geckoview.GeckoSession?=null
                scenario.onActivity{a->AccessPreferences.get(a).update(AccessPreferences.get(a).options.copy(enabled=false));a.workspace.create("http://127.0.0.1:${server.localPort}/hybrid")}
                await("fullscreen-first",90_000){checkMain{Workspace.peek()?.selected?.painted==true&&Workspace.peek()?.selected?.title=="HYBRID-MAGENTA"}&&pageIs(true)}
                main{original=Workspace.peek()?.selected?.session;RendererArena.transport=RendererArena.Transport.DIRECT_GECKO_SURFACE;PageTouchDispatch.arm=PageTouchDispatch.Arm.UNBUFFERED_140}
                scenario.onActivity{a->a.collapse(FloatingMode.CHAT)}
                await("direct-floating"){directFloating()&&pageIs(true)&&relayIdle()};save("direct-floating")
                pageTap();await("direct-click"){checkMain{Workspace.peek()?.selected?.title=="HYBRID-CYAN"}&&pageIs(false)}
                assertTrue("same GeckoSession in direct floating",checkMain{Workspace.peek()?.selected?.session===original})

                tapDescription("Open fullscreen")
                await("fullscreen-return",90_000){checkMain{Workspace.peek()?.host?.get()?.hasWindowFocus()==true&&Workspace.peek()?.floatingVisible==false}&&pageIs(false)}
                assertTrue("same GeckoSession after floating-to-fullscreen snapshot morph",checkMain{Workspace.peek()?.selected?.session===original})
                assertTrue("no Bubble relay in fullscreen",relayIdle());save("fullscreen-after-expand")

                main{requireNotNull(Workspace.peek()?.host?.get()).collapse(FloatingMode.CHAT)}
                await("direct-floating-return",90_000){directFloating()&&pageIs(false)&&relayIdle()}
                assertTrue("same GeckoSession after fullscreen-to-floating snapshot morph",checkMain{Workspace.peek()?.selected?.session===original})
                pageTap();await("direct-second-click"){checkMain{Workspace.peek()?.selected?.title=="HYBRID-MAGENTA"}&&pageIs(true)}
                save("direct-after-shrink")
                File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}.resolve("hybrid-direct.txt").writeText("PASS: Mozilla GeckoView SurfaceView remained steady-state renderer; relay stats idle; same GeckoSession survived floating->fullscreen and fullscreen->floating snapshot morphs. ${FullscreenHandoff.debugSummary()}\n")
            }
        }finally{context.stopService(Intent(context,BubbleService::class.java));server.close();serving.join(1000);health.edit().putBoolean("test-offer-v3",oldOffer).commit();auto.serviceInfo=auto.serviceInfo.apply{flags=oldFlags}}
    }
}
