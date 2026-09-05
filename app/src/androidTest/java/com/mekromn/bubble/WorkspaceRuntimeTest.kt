package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                val name = if (request.contains("/two")) "SECOND" else "FIRST"
                val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>$name</title><body style="background:#172b44;color:white;font:24px sans-serif"><h1>$name tab</h1><input placeholder="Type here"><p id="counter"></p><script>let n=0;setInterval(()=>{document.title='$name-'+(++n)+'-'+document.visibilityState;document.querySelector('#counter').textContent=n;},250)</script>""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(html)
            } } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)
                .setData(Uri.parse("http://127.0.0.1:${server.localPort}/one"))).use { scenario ->
                try {
                    waitFor(scenario) { it.painted && it.pageTitle.startsWith("FIRST-") }
                    var first: GeckoSession? = null
                    var firstId = ""
                    var secondId = ""
                    scenario.onActivity { activity ->
                        first = activity.selectedSession
                        firstId = activity.workspace.selectedId
                        secondId = activity.workspace.create("http://127.0.0.1:${server.localPort}/two").id
                    }
                    waitFor(scenario) { it.painted && it.pageTitle.startsWith("SECOND-") }
                    repeat(12) { i ->
                        scenario.onActivity { it.workspace.select(if (i % 2 == 0) firstId else secondId) }
                        Thread.sleep(120)
                    }
                    scenario.onActivity { it.workspace.select(firstId) }
                    waitFor(scenario) { it.painted && it.pageTitle.startsWith("FIRST-") }
                    scenario.onActivity { assertSame(first, it.selectedSession); it.showTabs(true) }
                    waitFor(scenario) { hasVisibleText(it.window.decorView, "Your workspace") }
                    awaitNativeFrame(scenario)
                    captureScreen("workspace-tray.png")
                    scenario.onActivity { activity ->
                        File(folder(), "tray-view-state.txt").writeText(describe(activity.window.decorView))
                        activity.showTabs(false)
                    }
                    scenario.recreate()
                    waitFor(scenario) { it.painted && it.selectedSession === first }
                    // Synthetic generation flag exercises the exact same retained-live lifecycle
                    // used for ChatGPT, without sending a real prompt to a public service.
                    scenario.onActivity { activity ->
                        activity.workspace.tabs.first { it.id == firstId }.generating = true
                        activity.workspace.applyPolicy()
                    }
                    var before = 0
                    scenario.onActivity { before = it.pageTitle.split('-')[1].toInt() }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    Thread.sleep(2500)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    waitFor(scenario) { it.selectedSession === first && it.painted }
                    scenario.onActivity {
                        assertFalse(it.isFinishing)
                        assertSame(first, it.selectedSession)
                        assertTrue("Retained session stopped advancing", it.pageTitle.split('-')[1].toInt() > before + 2)
                        it.workspace.selected!!.generating = false
                        it.workspace.applyPolicy()
                    }
                    awaitNativeFrame(scenario)
                    val pixels = capturePixels(scenario)
                    assertTrue("Recreated Activity has blank webpage pixels", hasContent(pixels))
                    savePixels(pixels, "recreated-compositor.png")
                    captureScreen("workspace-browser.png")
                } finally { captureScreen("workspace-final.png") }
            }
        } finally { server.close(); worker.join(1000) }
    }
    @Test fun publicSitesProduceActualPaintedDocuments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
            waitFor(scenario) { it.workspace.ready }
            val report = StringBuilder()
            for ((name, url) in listOf("google" to "https://www.google.com/", "chatgpt" to "https://chatgpt.com/")) {
                scenario.onActivity { it.workspace.create(url) }
                try {
                    val end = SystemClock.elapsedRealtime() + 120_000
                    var stableSince = 0L
                    var ready = false
                    while (!ready && SystemClock.elapsedRealtime() < end) {
                        var loaded = false
                        scenario.onActivity { loaded = it.painted && !it.workspace.selected!!.loading && it.workspace.selected!!.error == null }
                        if (!loaded) stableSince = 0
                        else {
                            val pixels = capturePixels(scenario)
                            val content = hasContent(pixels)
                            if (!content) stableSince = 0
                            else if (stableSince == 0L) stableSince = SystemClock.elapsedRealtime()
                            if (content && SystemClock.elapsedRealtime() - stableSince >= 5_000) {
                                savePixels(pixels, "$name-compositor.png")
                                ready = true
                            }
                            pixels.recycle()
                        }
                        if (!ready) Thread.sleep(1000)
                    }
                    scenario.onActivity {
                        report.append(name).append(": title=").append(it.pageTitle)
                            .append("; url=").append(it.workspace.selected?.url)
                            .append("; loading=").append(it.workspace.selected?.loading)
                            .append("; FCP=").append(it.painted).append("; nonblankStable=").append(ready).append('\n')
                        assertFalse(it.isFinishing)
                        assertNull(it.workspace.selected?.error)
                    }
                    assertTrue("$name never produced stable nonblank compositor pixels", ready)
                    awaitNativeFrame(scenario)
                } finally {
                    File(folder(), "public-sites.txt").writeText(report.toString())
                    captureScreen("public-$name.png")
                }
            }
        }
    }
    private fun waitFor(scenario: ActivityScenario<BrowserActivity>, condition: (BrowserActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 60_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { done = condition(it) }
            if (!done) Thread.sleep(100)
        }
        assertTrue("Browser runtime condition timed out", done)
    }
    private fun awaitNativeFrame(scenario: ActivityScenario<BrowserActivity>) {
        val frame = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            activity.window.decorView.postInvalidateOnAnimation()
        }
        assertTrue("Native window did not commit its frame", frame.await(15, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun capturePixels(scenario: ActivityScenario<BrowserActivity>): Bitmap {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        scenario.onActivity { it.geckoView.capturePixels().accept({ image -> bitmap = image; latch.countDown() }, { latch.countDown() }) }
        assertTrue("Compositor capture timed out", latch.await(15, TimeUnit.SECONDS))
        return requireNotNull(bitmap) { "No compositor pixels returned" }
    }
    private fun hasContent(image: Bitmap): Boolean {
        val colors = HashSet<Int>()
        var changed = 0
        var total = 0
        val baseline = image.getPixel(image.width / 2, image.height / 2) and 0x00f0f0f0
        for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
            val value = image.getPixel(x,y) and 0x00f0f0f0
            colors += value
            if (value != baseline) changed++
            total++
        }
        return colors.size > 8 && changed > total / 200
    }
    private fun hasVisibleText(view: View, text: String): Boolean {
        if (!view.isShown || view.alpha < 0.99f) return false
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) if (hasVisibleText(view.getChildAt(i),text)) return true
        return false
    }
    private fun describe(view: View, depth: Int = 0): String = buildString {
        append(" ".repeat(depth)).append(view.javaClass.simpleName).append(" ")
            .append(view.width).append('x').append(view.height).append(" alpha=").append(view.alpha)
            .append(" shown=").append(view.isShown)
        if (view is TextView) append(" text=").append(view.text.toString().take(100))
        append('\n')
        if (view is ViewGroup) for (i in 0 until view.childCount) append(describe(view.getChildAt(i),depth+1))
    }
    private fun folder() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun savePixels(image: Bitmap, name: String) { File(folder(),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
    private fun captureScreen(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { savePixels(it,name) }
    }
}
