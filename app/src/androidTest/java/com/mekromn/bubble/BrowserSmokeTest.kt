package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
    @Test fun rendersPixelsRunsJavascriptAndStaysOpen() {
        val server = ServerSocket(0)
        val requests = AtomicInteger()
        // Flat background colours alone do not produce a CONTENTFUL paint. Include real text.
        val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>Booting</title><style>html,body{margin:0;height:100%}body{background:#10b981;color:white;font:20px sans-serif}div{height:50%;background:#2463eb}p{margin:0;padding:24px}</style><div><p>Bubble compositor verification</p></div><p>JavaScript and native pixels</p><script>let n=0;setInterval(()=>document.title='ALIVE-'+(++n),250);</script>"""
        val worker = Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val body = html.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(body)
                } } catch (_: Exception) { if (server.isClosed) break }
            }
        }.apply { isDaemon = true; start() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                var alive = false
                var lastTitle = ""
                var paint = false
                try {
                    repeat(400) {
                        if (!alive) {
                            scenario.onActivity {
                                lastTitle = it.pageTitle; paint = it.painted
                                alive = paint && lastTitle.startsWith("ALIVE-")
                            }
                            if (!alive) Thread.sleep(100)
                        }
                    }
                    File(out, "renderer-state.txt").writeText("title=$lastTitle\ncontentfulPaint=$paint\nhttpRequests=${requests.get()}\n")
                    assertTrue("No paint/JS: title=$lastTitle, FCP=$paint, HTTP=${requests.get()}", alive)
                    Thread.sleep(12_000)
                    scenario.onActivity { assertFalse(it.isFinishing); assertTrue(it.pageTitle.removePrefix("ALIVE-").toInt() > 20) }
                    val captured = CountDownLatch(1)
                    var bitmap: Bitmap? = null
                    scenario.onActivity { it.geckoView.capturePixels().accept({ image -> bitmap = image; captured.countDown() }, { captured.countDown() }) }
                    assertTrue(captured.await(15, TimeUnit.SECONDS))
                    assertNotNull("No actual compositor pixels", bitmap)
                    val image = bitmap!!
                    assertNotEquals("Blank compositor", image.getPixel(image.width/2, image.height/4), image.getPixel(image.width/2, image.height*3/4))
                    File(out, "compositor.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    instrumentation.uiAutomation.takeScreenshot()?.let { screen -> File(out, "browser.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
            }
        } finally { server.close(); worker.join(1000) }
    }
}
