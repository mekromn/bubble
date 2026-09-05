package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

/** Public web signals are checked in actual page scripts, not by assuming registration worked.
 * UI menus use real long-press events on both Activity and floating-window controls. */
@RunWith(AndroidJUnit4::class)
class LiveToolsRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun webAndCrossOriginFramesReportForegroundBeforePageScriptsAndWhenParked() = fixture { scenario, port, _ ->
        var first = ""; var second = ""
        scenario.onActivity { first = it.workspace.selectedId; second = it.workspace.create("http://127.0.0.1:$port/two").id }
        await("Both top-level and cross-origin startup signals") { onMain {
            val ws = Workspace.peek()!!
            listOf(first, second).all { id -> ws.tabs.first { it.id == id }.title.split('|').let { it.size == 5 && it[2] == "true" && it[3] == "true" && it[4] == "true" } }
        } }
        val before = mutableMapOf<String, Int>()
        instrumentation.runOnMainSync { listOf(first, second).forEach { id -> before[id] = ticks(Workspace.peek()!!.tabs.first { it.id == id }.title) } }
        shell("input keyevent KEYCODE_HOME")
        await("Home leaves resident sessions behind a bubble") { scenario.state == Lifecycle.State.CREATED && onMain { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } }
        Thread.sleep(2200)
        instrumentation.runOnMainSync {
            val ws = Workspace.peek()!!
            listOf(first, second).forEach { id ->
                val tab = ws.tabs.first { it.id == id }
                assertTrue("Hidden-Activity page did not advance: ${tab.title}", ticks(tab.title) > before.getValue(id) + 3)
                assertSignals(tab.title); before[id] = ticks(tab.title)
            }
            assertTrue("Workspace could not be parked", BubbleService.active!!.park())
        }
        await("Parking removes the floating window") { onMain { BubbleService.active != null && BubbleService.active?.window == null } }
        Thread.sleep(2200)
        var report = ""
        instrumentation.runOnMainSync {
            val ws = Workspace.peek()!!
            listOf(first, second).forEach { id ->
                val tab = ws.tabs.first { it.id == id }
                assertTrue("Notification-parked page stopped advancing: ${tab.title}", ticks(tab.title) > before.getValue(id) + 3)
                assertSignals(tab.title); report += "${tab.title}\n"
            }
        }
        File(folder(), "v070-all-web-presence.txt").writeText("Format: tab | tick | inline startup foreground | current foreground | cross-origin frame foreground\n" + report)
        screenshot("v070-parked-live-tabs.png")
    }

    @Test fun realLongPressBackAndQuickTabsWorkWithoutHistoryAndInsideFloatingChat() = fixture { scenario, port, requests ->
        var first: GeckoSession? = null; var second: GeckoSession? = null; var firstId = ""; var secondId = ""
        scenario.onActivity { first = it.selectedSession; firstId = it.workspace.selectedId; it.selectedSession!!.purgeHistory() }
        await("First tab has no back history") { onMain { Workspace.peek()?.selected?.back == false } }
        touch({ it.contentDescription?.toString() == "Back in webpage" }, true)
        await("Back long-press opens page controls") { hasText("Forward") && hasText("Stop loading") && hasText("Refresh") }
        screenshot("v070-page-controls-no-history.png")
        val before = requests.get()
        touch({ it.text?.toString() == "Refresh" })
        await("Refresh kept the same session and loaded again") { requests.get() > before && onMain { Workspace.peek()?.selected?.session === first && Workspace.peek()?.selected?.painted == true } }
        scenario.onActivity { secondId = it.workspace.create("http://127.0.0.1:$port/two").id; second = it.selectedSession }
        await("Second tab renders") { onMain { Workspace.peek()?.selected?.title?.startsWith("LIVE-TWO|") == true && Workspace.peek()?.selected?.painted == true } }
        touch({ it.contentDescription?.startsWith("Workspace tabs,") == true }, true)
        await("Quick tabs menu appears") { hasText("Quick tabs") && hasText("Pinned") }
        screenshot("v070-quick-tabs.png")
        touch({ it.contentDescription?.startsWith("LIVE-ONE|") == true })
        await("Quick selection restores first session") { onMain { Workspace.peek()?.selectedId == firstId && Workspace.peek()?.selected?.session === first && Workspace.peek()?.quickMenuVisible == false } }
        touch({ it.contentDescription?.toString() == "Open interactive floating chat" })
        await("Interactive window is attached without fullscreen") { scenario.state == Lifecycle.State.CREATED && onMain {
            BubbleService.active?.window?.mode == FloatingMode.CHAT && BubbleService.active?.window?.isTransitioning == false && BubbleService.active?.window?.geckoView?.session === first
        } }
        touch({ it.contentDescription?.toString() == "Back in webpage" }, true)
        await("Floating page controls open") { hasText("Forward") && hasText("Refresh") }
        screenshot("v070-floating-page-controls.png")
        touch({ it.contentDescription?.toString() == "Close quick menu" })
        await("Floating menu dismissed") { onMain { Workspace.peek()?.quickMenuVisible == false } }
        touch({ it.contentDescription?.toString() == "Choose another conversation" }, true)
        await("Floating quick tabs open") { hasText("Quick tabs") }
        screenshot("v070-floating-quick-tabs.png")
        touch({ it.contentDescription?.startsWith("LIVE-TWO|") == true })
        await("Floating selection preserves exact second session") { onMain {
            Workspace.peek()?.selectedId == secondId && BubbleService.active?.window?.geckoView?.session === second && !Workspace.peek()!!.quickMenuVisible
        } }
        assertEquals("Quick tabs promoted fullscreen", Lifecycle.State.CREATED, scenario.state)
        touch({ it.contentDescription?.toString() == "Back in webpage" }, true)
        touch({ it.text?.toString() == "Find in conversation / page" })
        touch({ it.contentDescription?.toString() == "Find text" })
        Thread.sleep(450); instrumentation.sendStringSync("needle")
        await("Gecko finder reports the two actual page matches") { hasText("1 / 2") || hasText("2 / 2") }
        screenshot("v070-floating-find.png")
    }
    private fun ticks(title: String) = title.split('|').getOrNull(1)?.toIntOrNull() ?: 0
    private fun assertSignals(title: String) {
        val fields = title.split('|')
        assertEquals("Unexpected page report: $title", 5, fields.size)
        assertEquals("Script ran too late: $title", "true", fields[2])
        assertEquals("Page reports background state: $title", "true", fields[3])
        assertEquals("Frame reports background state: $title", "true", fields[4])
    }
    private fun onMain(test: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = test() }; return result }
    private fun await(message: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < deadline) { if (test()) return; Thread.sleep(100) }
        screenshot("v070-failure.png"); fail(message)
    }
    private fun node(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && match(n)) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        automation.windows.forEach { walk(it.root)?.let { n -> return n } }
        return null
    }
    private fun hasText(value: String) = node { it.text?.toString() == value } != null
    private fun touch(match: (AccessibilityNodeInfo) -> Boolean, hold: Boolean = false) {
        var found: AccessibilityNodeInfo? = null
        await("Could not find requested ${if (hold) "long-press" else "tap"} control") { found = node(match); found != null }
        val bounds = Rect(); found!!.getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { assertTrue(automation.injectInputEvent(down, true)) } finally { down.recycle() }
        Thread.sleep(if (hold) 700 else 60)
        val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { assertTrue(automation.injectInputEvent(up, true)) } finally { up.recycle() }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun folder() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun screenshot(name: String) { automation.takeScreenshot()?.let { image -> File(folder(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    private fun fixture(test: (ActivityScenario<BrowserActivity>, Int, AtomicInteger) -> Unit) {
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val server = ServerSocket(0); val requests = AtomicInteger()
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader(); val request = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                requests.incrementAndGet()
                val name = if (request.contains("/two")) "LIVE-TWO" else "LIVE-ONE"
                val frame = request.contains("/frame")
                val html = if (frame) """<!doctype html><script>const early=!document.hidden&&document.visibilityState==='visible'&&document.hasFocus();setInterval(()=>parent.postMessage({bubbleFixture:true,ok:early&&!document.hidden&&document.visibilityState==='visible'&&document.hasFocus()},'*'),200);</script><p>Embedded frame</p>"""
                    else """<!doctype html><script>const early=!document.hidden&&document.visibilityState==='visible'&&document.hasFocus();let frameOk=false;window.addEventListener('message',e=>{if(e.origin==='http://localhost:${server.localPort}'&&e.data.bubbleFixture)frameOk=e.data.ok;});let n=0;setInterval(()=>document.title='$name|'+(++n)+'|'+early+'|'+(!document.hidden&&document.visibilityState==='visible'&&document.hasFocus())+'|'+frameOk,250);</script><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{background:#122237;color:white;font:18px sans-serif;margin:16px}iframe{width:200px;height:90px;background:white}</style><h1>$name</h1><p>needle first</p><p>needle second</p><input aria-label="Composer"><iframe src="http://localhost:${server.localPort}/frame"></iframe>"""
                val bytes = html.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(bytes)
            } } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await("Workspace ready") { onMain { Workspace.peek()?.ready == true } }
                scenario.onActivity { activity ->
                    val ws = activity.workspace; val current = ws.create("http://127.0.0.1:${server.localPort}/one").id
                    ws.tabs.map { it.id }.filter { it != current }.forEach(ws::close)
                }
                await("Fixture rendered") { onMain { Workspace.peek()?.selected?.title?.startsWith("LIVE-ONE|") == true && Workspace.peek()?.selected?.painted == true } }
                test(scenario, server.localPort, requests)
            }
        } finally {
            instrumentation.runOnMainSync { QuickPanel.dismiss() }
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close(); worker.join(1000)
        }
    }
}
