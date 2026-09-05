package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

@RunWith(AndroidJUnit4::class)
class FloatingWorkspaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    // ActivityScenario may throw while the platform is between stable stages. Treat that as
    // not-yet-ready, never as a successful transition or a reason to drop the assertion.
    private fun state(scenario: ActivityScenario<BrowserActivity>) = runCatching { scenario.state }.getOrNull()

    @Test fun bubbleTapChoosesTabsAndTypesInsideFloatingWindow() = withPage { scenario, _ ->
        grantOverlay()
        var original: GeckoSession? = null; var id = ""
        scenario.onActivity { original = it.selectedSession; id = it.workspace.selectedId }
        shell("input keyevent KEYCODE_HOME")
        await { onMain { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } && state(scenario) == Lifecycle.State.CREATED }
        touchNode { it.contentDescription?.startsWith("Choose a conversation,") == true }
        await { onMain { BubbleService.active?.window?.mode == FloatingMode.CHOOSER && BubbleService.active?.window?.isTransitioning == false } }
        assertEquals("Chooser incorrectly reopened fullscreen", Lifecycle.State.CREATED, state(scenario))
        screenshot("v061-floating-chooser.png")
        touchNode { it.contentDescription?.startsWith("FLOAT-ONE") == true }
        await { onMain { BubbleService.active?.window?.mode == FloatingMode.CHAT && BubbleService.active?.window?.geckoView?.session === original && BubbleService.active?.window?.isTransitioning == false } }
        Thread.sleep(500)
        assertEquals("Selecting a conversation incorrectly opened fullscreen", Lifecycle.State.CREATED, state(scenario))
        val pixels = captureFloatingPixels()
        assertTrue(pixels.width > 100 && pixels.height > 100)
        val colors = HashSet<Int>()
        for (y in 0 until pixels.height step 8) for (x in 0 until pixels.width step 8) colors += pixels.getPixel(x, y) and 0x00f0f0f0
        assertTrue("Floating compositor is blank", colors.size > 8)
        File(folder(), "v061-floating-compositor.png").outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var x = 0f; var y = 0f
        instrumentation.runOnMainSync {
            val view = BubbleService.active!!.window!!.geckoView!!
            assertTrue("Floating view is not hardware accelerated", view.isHardwareAccelerated)
            val p = IntArray(2); view.getLocationOnScreen(p)
            x = p[0] + 70 * context.resources.displayMetrics.density
            y = p[1] + 104 * context.resources.displayMetrics.density
        }
        tap(x, y)
        await { onMain { BubbleService.active?.window?.geckoView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true } }
        instrumentation.waitForIdleSync(); instrumentation.sendStringSync("floating")
        await { onMain { Workspace.peek()?.selected?.title?.startsWith("TYPED:floating") == true } }
        screenshot("v061-floating-keyboard.png")
        shell("input keyevent KEYCODE_BACK")
        await { onMain { BubbleService.active?.window?.isTransitioning == false } }
        if (onMain { BubbleService.active?.window?.mode == FloatingMode.CHAT }) {
            touchNode { it.contentDescription == "Open fullscreen" }
        } else {
            touchNode { it.contentDescription?.startsWith("Choose a conversation,") == true }
            await { onMain { BubbleService.active?.window?.isTransitioning == false } }
            touchNode { it.contentDescription?.startsWith("TYPED:floating") == true }
            touchNode { it.contentDescription == "Open fullscreen" }
        }
        await { state(scenario) == Lifecycle.State.RESUMED }
        scenario.onActivity { assertSame(original, it.selectedSession); assertEquals(id, it.workspace.selectedId) }
        await { onMain { BubbleService.active == null } }
        screenshot("v061-compact-fullscreen.png")
        shell("input keyevent KEYCODE_BACK")
        await { onMain { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } && state(scenario) == Lifecycle.State.CREATED }
    }

    @Test fun allTabsRemainVisibleToGeckoWhileTheActivityIsBehindOtherApps() = withPage { scenario, port ->
        grantOverlay()
        var first = ""; var second = ""
        scenario.onActivity { first = it.workspace.selectedId; second = it.workspace.create("http://127.0.0.1:$port/two").id }
        await { onMain { Workspace.peek()?.tabs?.firstOrNull { it.id == second }?.title?.startsWith("FLOAT-TWO") == true } }
        var beforeFirst = 0; var beforeSecond = 0
        instrumentation.runOnMainSync {
            val ws = Workspace.peek()!!; beforeFirst = tick(ws.tabs.first { it.id == first }.title); beforeSecond = tick(ws.tabs.first { it.id == second }.title)
        }
        shell("input keyevent KEYCODE_HOME")
        await { onMain { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } && state(scenario) == Lifecycle.State.CREATED }
        Thread.sleep(3500)
        instrumentation.runOnMainSync {
            val ws = Workspace.peek()!!; val one = ws.tabs.first { it.id == first }; val two = ws.tabs.first { it.id == second }
            assertTrue("First tab stopped running", tick(one.title) > beforeFirst + 3)
            assertTrue("Second tab stopped running", tick(two.title) > beforeSecond + 3)
            assertTrue("First document became hidden", one.title.endsWith("|visible"))
            assertTrue("Second document became hidden", two.title.endsWith("|visible"))
        }
        screenshot("v061-background-live-tabs.png")
    }

    @Test fun nativeAndroidPipCanSwitchBetweenTheSameSessions() = withPage { scenario, port ->
        var first: GeckoSession? = null; var second: GeckoSession? = null
        scenario.onActivity { first = it.selectedSession; it.workspace.create("http://127.0.0.1:$port/two"); second = it.selectedSession }
        await { onMain { Workspace.peek()?.selected?.painted == true } }
        scenario.onActivity { assertTrue("System PiP entry failed", it.enterNativePip()) }
        await { onMain { Workspace.peek()?.host?.get()?.isInPictureInPictureMode == true } }
        Thread.sleep(700); screenshot("v061-native-android-pip.png")
        context.sendBroadcast(Intent(context, PipTabReceiver::class.java).setAction(PipTabReceiver.PREVIOUS))
        await { onMain { Workspace.peek()?.selected?.session === first } }
        context.sendBroadcast(Intent(context, PipTabReceiver::class.java).setAction(PipTabReceiver.NEXT))
        await { onMain { Workspace.peek()?.selected?.session === second } }
        instrumentation.runOnMainSync { Workspace.peek()?.host?.get()?.finish() }
        await { state(scenario) == Lifecycle.State.DESTROYED }
    }
    private fun tick(title: String) = title.split('|').getOrNull(1)?.toIntOrNull() ?: 0
    private fun grantOverlay() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private fun onMain(test: () -> Boolean): Boolean { var value = false; instrumentation.runOnMainSync { value = test() }; return value }
    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        screenshot("v061-floating-failure.png"); fail("Floating interaction condition timed out")
    }
    private fun touchNode(match: (AccessibilityNodeInfo) -> Boolean) {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || found != null) return
            if (match(node) && node.isVisibleToUser) { found = node; return }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        await { found = null; automation.windows.forEach { walk(it.root) }; found != null }
        val bounds = Rect(); found!!.getBoundsInScreen(bounds); tap(bounds.exactCenterX(), bounds.exactCenterY())
    }
    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)) }
        finally { down.recycle(); up.recycle() }
    }
    private fun captureFloatingPixels(): Bitmap {
        val latch = CountDownLatch(1); var result: Bitmap? = null
        instrumentation.runOnMainSync { BubbleService.active!!.window!!.geckoView!!.capturePixels().accept({ result = it; latch.countDown() }, { latch.countDown() }) }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); return requireNotNull(result)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun folder() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun screenshot(name: String) { automation.takeScreenshot()?.let { image -> File(folder(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    private fun withPage(test: (ActivityScenario<BrowserActivity>, Int) -> Unit) {
        val oldFlags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader(); val request = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                val name = if (request.contains("/two")) "FLOAT-TWO" else "FLOAT-ONE"
                val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>$name</title><style>body{margin:0;background:#122237;color:#edf2ff;font:18px sans-serif}h1{padding:16px;font-size:22px;margin:0}input{position:absolute;left:16px;top:80px;width:220px;height:48px;box-sizing:border-box;font-size:20px}</style><h1>$name · live chat</h1><input aria-label="Floating test composer" autocomplete="off"><p style="padding:130px 16px 16px">A real Gecko page inside a floating window.</p><script>let n=0;setInterval(()=>{const v=document.querySelector('input').value;document.title=(v?'TYPED:'+v:'$name')+'|'+(++n)+'|'+document.visibilityState;},250);</script>""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray()); socket.getOutputStream().write(html)
            }} catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/one"))).use { scenario ->
                await { onMain { Workspace.peek()?.selected?.title?.startsWith("FLOAT-ONE") == true && Workspace.peek()?.selected?.painted == true } }
                scenario.onActivity { activity -> val current = activity.workspace.selectedId; activity.workspace.tabs.map { it.id }.filter { it != current }.forEach(activity.workspace::close) }
                test(scenario, server.localPort)
            }
        } finally {
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close(); worker.join(1000)
        }
    }
}
