package com.mekromn.bubble

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch and keys. Wait for the cold emulator IME, not an arbitrary 600ms delay. */
@RunWith(AndroidJUnit4::class)
class BrowserInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun typingAndSystemBackWorkThroughTheNativeBrowser() {
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>INPUT-READY</title><style>body{margin:0;background:#14253b;color:#edf2ff;font:18px sans-serif}h1{margin:16px;font-size:22px}input{position:absolute;left:16px;top:80px;height:48px;width:240px;font-size:20px;box-sizing:border-box}</style><h1>Native input verification</h1><input aria-label="Test input" autocomplete="off" onfocus="document.title='INPUT-FOCUSED'" oninput="document.title='TYPED:'+this.value">""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(html)
            }} catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val context = instrumentation.targetContext
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                try {
                    waitFor(scenario) { it.painted && it.pageTitle == "INPUT-READY" }
                    var x = 0f; var y = 0f
                    scenario.onActivity {
                        val p = IntArray(2); it.geckoView.getLocationOnScreen(p)
                        x = p[0] + 80 * it.resources.displayMetrics.density; y = p[1] + 104 * it.resources.displayMetrics.density
                    }
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, x, y, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true)); assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true)) }
                    finally { down.recycle(); up.recycle() }
                    waitFor(scenario) { it.pageTitle == "INPUT-FOCUSED" && ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    instrumentation.waitForIdleSync()
                    instrumentation.sendStringSync("bubble")
                    waitFor(scenario) { it.pageTitle == "TYPED:bubble" }
                    capture("web-input.png")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    waitFor(scenario) { ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                    scenario.onActivity { assertFalse(it.isFinishing); it.showTabs(true) }
                    waitFor(scenario) { hasShownDescription(it.window.decorView, "Close workspace") }
                    Thread.sleep(250)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    waitFor(scenario) { !hasShownDescription(it.window.decorView, "Close workspace") }
                    scenario.onActivity { assertFalse(it.isFinishing); assertEquals("TYPED:bubble", it.pageTitle) }
                    val meter = FrameMeter()
                    scenario.onActivity { meter.start(it) }
                    repeat(12) { index -> scenario.onActivity { it.showTabs(index % 2 == 0) }; Thread.sleep(270) }
                    var report = ""
                    scenario.onActivity { report = meter.report(it); meter.stop() }
                    File(folder(), "native-emulator-frame-timing.txt").writeText(report)
                } finally { capture("after-native-back.png") }
            }
        } finally { server.close(); worker.join(1000) }
    }
    private fun hasShownDescription(view: View, description: String): Boolean {
        if (!view.isShown) return false
        if (view.contentDescription?.toString() == description) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) if (hasShownDescription(view.getChildAt(i), description)) return true
        return false
    }
    private fun waitFor(scenario: ActivityScenario<BrowserActivity>, predicate: (BrowserActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 40_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < end) { scenario.onActivity { done = predicate(it) }; if (!done) Thread.sleep(100) }
        assertTrue("Native input/navigation condition timed out", done)
    }
    private fun folder() = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun capture(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { image -> File(folder(),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } }
}
