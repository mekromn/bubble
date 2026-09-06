package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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

/** Real touch and keys. Web control geometry comes from Gecko accessibility rather than a
 * density-dependent hard-coded CSS coordinate; the actual input is still injected touch/keys. */
@RunWith(AndroidJUnit4::class)
class BrowserInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
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
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                try {
                    waitFor(scenario) { it.painted && it.pageTitle == "INPUT-READY" }
                    var input: AccessibilityNodeInfo? = null
                    waitUntil {
                        input = findNode { n ->
                            n.packageName?.toString() == context.packageName &&
                                (n.contentDescription?.toString() == "Test input" || n.hintText?.toString() == "Test input")
                        }
                        input != null
                    }
                    val bounds = Rect(); input!!.getBoundsInScreen(bounds)
                    assertTrue("Gecko input accessibility bounds are empty", !bounds.isEmpty)
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    val up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)) }
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
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            server.close(); worker.join(1000)
        }
    }
    private fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && match(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        automation.windows.sortedByDescending { it.layer }.forEach { walk(it.root)?.let { result -> return result } }
        return null
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        fail("Native accessibility/input condition timed out")
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
    private fun capture(name: String) { automation.takeScreenshot()?.let { image -> File(folder(),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } }
}
