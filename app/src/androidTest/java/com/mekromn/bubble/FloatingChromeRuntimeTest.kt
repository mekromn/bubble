package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingChromeRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun floatingChatExposesRefreshShareAndSwipeDownMinimize() {
        val oldFlags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>FLOAT-CHROME</title><style>body{background:#111;color:white;font:20px sans-serif}</style><h1>Floating chrome fixture</h1>""".toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(html)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await { main { Workspace.peek()?.ready == true } }
                scenario.onActivity { activity ->
                    AccessPreferences.get(activity).update(AccessPreferences.get(activity).options.copy(enabled = false))
                    val id = activity.workspace.create("http://127.0.0.1:${server.localPort}/").id
                    activity.workspace.tabs.map { it.id }.filter { it != id }.forEach(activity.workspace::close)
                }
                await { main { Workspace.peek()?.selected?.title == "FLOAT-CHROME" && Workspace.peek()?.selected?.painted == true } }
                scenario.onActivity { it.collapse(FloatingMode.CHAT) }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT && BubbleService.active?.window?.isTransitioning == false } }

                assertNotNull(node("Refresh floating page"))
                assertNotNull(node("Share floating page"))
                val handle = requireNotNull(node("Swipe down to minimize floating window"))
                val bounds = Rect(); handle.getBoundsInScreen(bounds)
                val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
                val down = SystemClock.uptimeMillis()
                event(down, MotionEvent.ACTION_DOWN, x, y)
                event(down, MotionEvent.ACTION_MOVE, x, y + 56f * context.resources.displayMetrics.density)
                event(down, MotionEvent.ACTION_UP, x, y + 56f * context.resources.displayMetrics.density)
                await { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE && BubbleService.active?.window?.isTransitioning == false } }
            }
        } finally {
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close(); worker.join(1000)
        }
    }

    private fun node(description: String): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && n.contentDescription?.toString() == description) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        automation.windows.sortedByDescending { it.layer }.forEach { walk(it.root)?.let { found -> return found } }
        return null
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(automation.injectInputEvent(e, true)) } finally { e.recycle() }
    }
    private fun main(test: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = test() }; return result }
    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        fail("Floating chrome runtime condition timed out")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
