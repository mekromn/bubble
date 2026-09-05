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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
    @Test fun rendersPixelsRunsJavascriptAndStaysOpen() {
        val server = ServerSocket(0)
        val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>Booting</title><style>html,body{margin:0;height:100%}body{background:#10b981}div{height:50%;background:#2463eb}</style><div></div><script>let n=0;setInterval(()=>document.title='ALIVE-'+(++n),250);</script>"""
        val worker = Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = html.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(body)
                } } catch (_: Exception) { if (server.isClosed) break }
            }
        }.apply { isDaemon = true; start() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                var alive = false
                repeat(300) {
                    if (!alive) {
                        scenario.onActivity { alive = it.painted && it.pageTitle.startsWith("ALIVE-") }
                        if (!alive) Thread.sleep(100)
                    }
                }
                assertTrue("No compositor paint / JavaScript progress", alive)
                Thread.sleep(12_000)
                scenario.onActivity { assertFalse(it.isFinishing); assertTrue(it.pageTitle.removePrefix("ALIVE-").toInt() > 20) }
                val captured = CountDownLatch(1)
                var bitmap: Bitmap? = null
                scenario.onActivity { it.geckoView.capturePixels().accept({ image -> bitmap = image; captured.countDown() }, { captured.countDown() }) }
                assertTrue(captured.await(15, TimeUnit.SECONDS))
                assertNotNull("No actual compositor pixels", bitmap)
                val image = bitmap!!
                assertNotEquals("Blank compositor", image.getPixel(image.width/2, image.height/4), image.getPixel(image.width/2, image.height*3/4))
                val out = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
                File(out, "compositor.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screen -> File(out, "browser.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
        } finally { server.close(); worker.join(1000) }
    }
}
