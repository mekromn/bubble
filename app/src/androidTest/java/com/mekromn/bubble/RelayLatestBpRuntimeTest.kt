package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
    private fun tapDescription(description: String) {
        var found: Rect?=null
        fun walk(node:AccessibilityNodeInfo?) {
            if(node==null||found!=null)return
            if(node.isVisibleToUser&&node.contentDescription==description){found=Rect().also{node.getBoundsInScreen(it)};return}
            for(i in 0 until node.childCount)walk(node.getChild(i))
        }
        await(description){found=null;auto.windows.forEach{walk(it.root)};found!=null}
        val r=requireNotNull(found);tap(r.exactCenterX(),r.exactCenterY())
    }
    @Test fun actualBubblePixelsInputTabSwapResizeAndFullscreenReturn() {
        val server=ServerSocket(0)
        val serving=Thread {
            while(!server.isClosed)try{server.accept().use { s ->
                s.soTimeout=5000;val reader=s.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()){}
                val id=if(request.contains("/two"))"B" else "A"
                val color=if(id=="A")"rgb(220,30,180)" else "rgb(20,200,210)"
                val html="""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>RELAY-$id</title>
                <style>html,body{margin:0;height:100%;overflow:hidden}canvas{position:fixed;inset:0;width:100%;height:100%}button,input{position:absolute;left:10%;width:80%;height:10%;z-index:2}button{top:40%}input{top:55%}</style>
                <canvas id="c"></canvas><button onclick="document.title='CLICKED-$id'">Relay button $id</button><input oninput="document.title='TYPED-'+this.value" aria-label="Relay composer">
                <script>const c=document.querySelector('canvas'),g=c.getContext('2d');function paint(){c.width=innerWidth;c.height=innerHeight;g.fillStyle='$color';g.fillRect(0,0,c.width,c.height);g.fillStyle='yellow';g.fillRect(8,8,32,32)}addEventListener('resize',paint);paint();</script>""".toByteArray()
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
                scenario.onActivity{a->a.geckoView.postOnAnimation{a.collapse(FloatingMode.CHAT)}}
                await("floating"){checkMain{BubbleService.active?.window?.mode==FloatingMode.CHAT&&Workspace.peek()?.floatingVisible==true}}
                await("floating-red"){pageHasColor(true)};save("floating-A")
                assertEquals(6L,NativeAhbBridge.nativeDebugStats()[7]);assertEquals(4L,NativeAhbBridge.nativeDebugStats()[8]);assertEquals(1L,NativeAhbBridge.nativeDebugStats()[9])
                pageTap(.45f);await("click"){checkMain{Workspace.peek()?.selected?.title=="CLICKED-A"}}
                pageTap(.60f);shell("input text relay")
                await("typing"){checkMain{Workspace.peek()?.selected?.title=="TYPED-relay"}}
                shell("input keyevent KEYCODE_BACK")
                main{second=Workspace.peek()!!.create("http://127.0.0.1:${server.localPort}/two").id;BubbleService.active!!.window!!.openChat(second)}
                await("cold-tab-cyan"){pageHasColor(false)};save("floating-cold-B")
                repeat(6){i->val red=i%2==0;main{BubbleService.active!!.window!!.openChat(if(red)first else second)};await("tab-$i"){pageHasColor(red)}}
                main{val v=requireNotNull(inputHost());val root=v.parent as View;val p=root.layoutParams as WindowManager.LayoutParams;p.width-=40;v.context.getSystemService(WindowManager::class.java).updateViewLayout(root,p)}
                await("resized-cyan"){pageHasColor(false)};save("resized")
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
