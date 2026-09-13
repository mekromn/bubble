package com.mekromn.bubble

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import java.io.File
import org.junit.Assert.*

/** Test-only, real Android input -> existing Gecko/APZ -> local DOM/pixels.
 * No timings are interpreted as input-to-display latency. No hooks/logging ship.
 */
internal class PageTouchChecks(private val inst: Instrumentation) {
    private val context = inst.targetContext
    private val auto = inst.uiAutomation
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun host(): View = requireNotNull(Workspace.peek()?.selected?.session?.textInput?.view)
    private fun title(): String { var value="";main { value=Workspace.peek()?.selected?.title.orEmpty() };return value }
    private fun await(label: String, check: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while(SystemClock.elapsedRealtime()<end) { if(check())return;Thread.sleep(50) }
        save("failure-$label");fail("Page input check timed out: $label; title=${title()}")
    }
    private fun save(label: String) {
        auto.takeScreenshot()?.let { image ->
            File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}
                .resolve("page-input-$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
    }
    private fun summary(): Map<String, Int> = title().split('|').drop(1).mapNotNull {
        val split=it.split('=');val value=split.getOrNull(1)?.toIntOrNull()
        if(split.size==2&&value!=null) split[0] to value else null
    }.toMap()
    private fun counts(label:String, down:Int, up:Int, cancel:Int, peak:Int) {
        await(label) { val s=summary();s["d"]==down&&s["u"]==up&&s["c"]==cancel&&s["a"]==0&&s["p"]==peak }
        val s=summary();assertEquals("$label input order/timestamp errors",0,s["e"])
        assertTrue("$label actual moves reached content",(s["m"]?:0)>0)
    }
    fun run(label: String, inputUrl: String, restoreUrl: String) {
        val evidence=File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}
        main { Workspace.peek()!!.navigate(inputUrl) }
        await("$label-ready") { title().startsWith("INPUT|d=0|") }
        var left=0f;var top=0f;var width=0f;var height=0f
        main { val h=host();val p=IntArray(2);h.getLocationOnScreen(p)
            assertTrue("Real input host attached",h.isAttachedToWindow)
            assertTrue("Host hardware-accelerated",h.isHardwareAccelerated)
            left=p[0].toFloat();top=p[1].toFloat();width=h.width.toFloat();height=h.height.toFloat() }
        assertTrue(width>100&&height>100)
        fun send(start:Long, action:Int, positions:Array<Pair<Float,Float>>, stylus:Boolean=false) {
            val properties=Array(positions.size){i->MotionEvent.PointerProperties().apply{
                id=i;toolType=if(stylus)MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER}}
            val coordinates=Array(positions.size){i->MotionEvent.PointerCoords().apply{
                x=left+positions[i].first*width;y=top+positions[i].second*height
                pressure=.7f;size=.1f
                if(stylus)setAxisValue(MotionEvent.AXIS_TILT,.25f)
            }}
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,positions.size,
                properties,coordinates,0,0,1f,1f,0,0,
                if(stylus)InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,0)
            try { assertTrue("$label real event injection action=$action",auto.injectInputEvent(event,true)) }
            finally {event.recycle()}
        }
        fun one(action:Int,start:Long,x:Float,y:Float,stylus:Boolean=false)=send(start,action,arrayOf(x to y),stylus)
        var start=SystemClock.uptimeMillis()
        one(MotionEvent.ACTION_DOWN,start,.35f,.5f)
        repeat(8){i->Thread.sleep(12);one(MotionEvent.ACTION_MOVE,start,.35f+(i+1)*.025f,.5f)}
        one(MotionEvent.ACTION_UP,start,.55f,.5f)
        counts("$label-drag",1,1,0,1)
        assertTrue("Endpoint coordinates kept",kotlin.math.abs((summary()["x"]?:0)-5500)<250)
        val clicksBeforeCancel=summary()["k"]?:0
        start=SystemClock.uptimeMillis()
        one(MotionEvent.ACTION_DOWN,start,.35f,.5f)
        Thread.sleep(20);one(MotionEvent.ACTION_MOVE,start,.4f,.5f)
        one(MotionEvent.ACTION_CANCEL,start,.4f,.5f)
        counts("$label-cancel",2,1,1,1)
        assertEquals("Canceled stream must not click",clicksBeforeCancel,summary()["k"])
        // Two actual pointers exercise Gecko's existing routing. touch-action:none
        // deliberately tests DOM pointer fidelity, not pinch-zoom performance.
        start=SystemClock.uptimeMillis()
        one(MotionEvent.ACTION_DOWN,start,.4f,.5f)
        send(start,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),arrayOf(.4f to .5f,.6f to .5f))
        repeat(6){i->Thread.sleep(15);val amount=(i+1)*.015f
            send(start,MotionEvent.ACTION_MOVE,arrayOf((.4f-amount) to .5f,(.6f+amount) to .5f))}
        send(start,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),arrayOf(.31f to .5f,.69f to .5f))
        one(MotionEvent.ACTION_UP,start,.31f,.5f)
        counts("$label-two-pointer",4,3,1,2)
        // A fresh gesture after cancellation/multitouch must still click normally.
        start=SystemClock.uptimeMillis();one(MotionEvent.ACTION_DOWN,start,.65f,.5f)
        Thread.sleep(35);one(MotionEvent.ACTION_UP,start,.65f,.5f)
        counts("$label-restart",5,4,1,2)
        await("$label-restart-click") { (summary()["k"]?:0)>clicksBeforeCancel }
        start=SystemClock.uptimeMillis();one(MotionEvent.ACTION_DOWN,start,.45f,.5f,true)
        repeat(4){i->Thread.sleep(16);one(MotionEvent.ACTION_MOVE,start,.45f+(i+1)*.025f,.5f,true)}
        one(MotionEvent.ACTION_UP,start,.55f,.5f,true)
        counts("$label-stylus",6,5,1,2)
        assertTrue("Stylus type preserved",(summary()["s"]?:0)>0)
        assertTrue("Pressure reaches DOM",(summary()["r"]?:0) in 1..1000)
        // A frame incorporating the final input must visibly change the actual page.
        await("$label-response-pixels") {
            val image=auto.takeScreenshot()?:return@await false
            try { val c=image.getPixel((left+width*.15f).toInt(),(top+height*.75f).toInt())
                Color.green(c)>130&&Color.red(c)<100&&Color.blue(c)>130
            } finally { image.recycle() }
        }
        evidence.resolve("page-input-$label.txt").writeText(
            "PASS: actual $label input host; finger drag, cancellation, two pointers, fresh tap, stylus, coordinate/order/timestamp/pressure checks and visible response.\n"+
            "${title()}\nNo touch-to-photon or physical touchscreen timing claim. No claim of Chromium fling parity.\n")
        save(label)
        main { Workspace.peek()!!.navigate(restoreUrl) }
        await("$label-restored") { title()=="RELAY-A" }
    }
    companion object {
        val HTML="""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;width:100%;height:100%;overflow:hidden;touch-action:none;background:rgb(220,30,180);color:white}</style>
        <body>Native input fidelity check<script>
        let d=0,m=0,u=0,c=0,p=0,e=0,k=0,x=0,s=0,r=0,last=0;
        const active=new Set();
        function publish(){document.title='INPUT|d='+d+'|u='+u+'|c='+c+'|a='+active.size+'|p='+p+'|e='+e+'|m='+m+'|k='+k+'|x='+x+'|s='+s+'|r='+r;}
        for(const name of ['pointerdown','pointermove','pointerup','pointercancel']) addEventListener(name,event=>{
          if(name==='pointermove'&&!active.has(event.pointerId))return;
          if(event.timeStamp<last)e++;last=event.timeStamp;
          if(name==='pointerdown'){if(active.has(event.pointerId))e++;active.add(event.pointerId);d++;p=Math.max(p,active.size);}
          else if(name==='pointermove'){m++;r=Math.max(r,Math.round(event.pressure*1000));}
          else {if(!active.delete(event.pointerId))e++;if(name==='pointerup')u++;else c++;}
          if(event.pointerType==='pen')s++;
          x=Math.round(event.clientX/innerWidth*10000);
          document.body.style.background=x>5000?'rgb(20,200,210)':'rgb(220,30,180)';publish();
        });
        addEventListener('click',()=>{k++;publish()});publish();
        </script></body>"""
    }
}
