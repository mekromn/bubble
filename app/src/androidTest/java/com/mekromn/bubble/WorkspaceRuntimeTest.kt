package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

@RunWith(AndroidJUnit4::class)
class WorkspaceRuntimeTest {
    @Test fun tabSwitchingRecreationAndOverlayDoNotRecreateSessions() {
        val server=ServerSocket(0)
        val worker=Thread {
            while(!server.isClosed)try{server.accept().use{socket->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()){ }
                val name=if(request.contains("/two"))"SECOND" else "FIRST"
                val html="""<!doctype html><meta name="viewport" content="width=device-width"><title>$name</title><body style="background:#172b44;color:white;font:24px sans-serif"><h1>$name tab</h1><input placeholder="Type here"><p id="counter"></p><script>let n=0;setInterval(()=>{document.title='$name-'+(++n);document.querySelector('#counter').textContent=n;},250)</script>""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray());socket.getOutputStream().write(html)
            }}catch(_:Exception){if(server.isClosed)break}
        }.apply{isDaemon=true;start()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        try{
            ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/one"))).use{scenario->
                waitFor(scenario){it.painted&&it.pageTitle.startsWith("FIRST-")}
                var first:GeckoSession?=null;var firstId="";var secondId=""
                scenario.onActivity{activity->first=activity.selectedSession;firstId=activity.workspace.selectedId;secondId=activity.workspace.create("http://127.0.0.1:${server.localPort}/two").id}
                waitFor(scenario){it.painted&&it.pageTitle.startsWith("SECOND-")}
                repeat(12){i->scenario.onActivity{it.workspace.select(if(i%2==0)firstId else secondId)};Thread.sleep(120)}
                scenario.onActivity{it.workspace.select(firstId)}
                waitFor(scenario){it.painted&&it.pageTitle.startsWith("FIRST-")}
                scenario.onActivity{assertSame(first,it.selectedSession);it.showTabs(true)}
                Thread.sleep(350);captureScreen("workspace-tray.png")
                scenario.onActivity{it.showTabs(false)}
                scenario.recreate()
                waitFor(scenario){it.painted&&it.selectedSession===first}
                scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(2000);scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario){it.selectedSession===first && it.painted}
                scenario.onActivity{assertFalse(it.isFinishing);assertSame(first,it.selectedSession)}
                captureScreen("workspace-browser.png")
            }
        }finally{server.close();worker.join(1000)}
    }
    @Test fun publicSitesProduceActualPaintedDocuments() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BrowserActivity>(Intent(context,BrowserActivity::class.java)).use{scenario->
            waitFor(scenario){it.workspace.ready}
            val report=StringBuilder()
            for((name,url) in listOf("google" to "https://www.google.com/","chatgpt" to "https://chatgpt.com/")){
                scenario.onActivity{it.workspace.create(url)}
                waitFor(scenario){it.painted&&!it.workspace.selected!!.loading}
                Thread.sleep(2000)
                scenario.onActivity{
                    assertFalse(it.isFinishing);assertNull(it.workspace.selected?.error)
                    assertTrue("Empty title on $name",it.pageTitle.isNotBlank())
                    report.append(name).append(": title=").append(it.pageTitle).append("; url=").append(it.workspace.selected?.url).append('\n')
                }
                captureScreen("public-$name.png")
            }
            // The report distinguishes a painted challenge page from a working logged-in app.
            val folder=File(context.getExternalFilesDir(null),"evidence").apply{mkdirs()}
            File(folder,"public-sites.txt").writeText(report.toString())
        }
    }
    private fun waitFor(scenario:ActivityScenario<BrowserActivity>,condition:(BrowserActivity)->Boolean){
        val end=System.currentTimeMillis()+60_000
        var done=false
        while(!done&&System.currentTimeMillis()<end){scenario.onActivity{done=condition(it)};if(!done)Thread.sleep(100)}
        assertTrue("Browser runtime condition timed out",done)
    }
    private fun captureScreen(name:String){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()?:return
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence").apply{mkdirs()}
        File(folder,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
