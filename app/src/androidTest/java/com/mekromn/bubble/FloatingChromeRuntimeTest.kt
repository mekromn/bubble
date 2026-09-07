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

    @Test fun floatingPagePillHandlesHistorySwitcherMinimizeAndMatchedFullscreenRoundTrip() {
        val oldFlags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val path = first.split(' ').getOrNull(1).orEmpty()
                    val title = if (path.startsWith("/two")) "FLOAT-TWO" else "FLOAT-ONE"
                    val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>$title</title><style>body{background:#111;color:white;font:20px sans-serif}</style><h1>$title</h1>""".toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(html)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        try {
            prepareOverlayAccess()
            val one = "http://127.0.0.1:${server.localPort}/one"
            val two = "http://127.0.0.1:${server.localPort}/two"
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await { main { Workspace.peek()?.ready == true } }
                scenario.onActivity { activity ->
                    AccessPreferences.get(activity).update(AccessPreferences.get(activity).options.copy(enabled = false))
                    val id = activity.workspace.create(one).id
                    activity.workspace.tabs.map { it.id }.filter { it != id }.forEach(activity.workspace::close)
                }
                await { main { Workspace.peek()?.selected?.title == "FLOAT-ONE" && Workspace.peek()?.selected?.painted == true } }
                scenario.onActivity { it.workspace.navigate(two) }
                await { main { Workspace.peek()?.selected?.title == "FLOAT-TWO" && Workspace.peek()?.selected?.back == true } }
                scenario.onActivity { it.collapse(FloatingMode.CHAT) }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }

                val chatPill = "Swipe up for chats, left for back, right for forward, or down to minimize floating window"
                val chooserPill = "Swipe up for last tab or down to minimize floating window"
                await {
                    automation.waitForIdle(50, 500)
                    node("Refresh floating page") != null && node("Share floating page") != null && node(chatPill) != null
                }

                // LEFT = webpage Back.
                swipe(node(chatPill)!!, -44f, 0f)
                await { main { Workspace.peek()?.selected?.title == "FLOAT-ONE" && Workspace.peek()?.selected?.forward == true } }
                // RIGHT = webpage Forward.
                swipe(node(chatPill)!!, 44f, 0f)
                await { main { Workspace.peek()?.selected?.title == "FLOAT-TWO" && Workspace.peek()?.selected?.back == true } }

                // UP = Your chats.
                swipe(node(chatPill)!!, 0f, -44f)
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHOOSER } }
                await { node(chooserPill) != null && node("Resize conversation chooser") != null }
                Thread.sleep(650)
                assertTrue(main { BubbleService.active?.window?.mode == FloatingMode.CHOOSER })

                // UP on Your chats = return to the same/last selected tab.
                val before = selectedId()
                swipe(node(chooserPill)!!, 0f, -44f)
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }
                await { node(chatPill) != null && node("Open fullscreen") != null }
                assertEquals(before, selectedId())

                // Exercise the new reverse half of the matched transition. The outer floating card
                // must grow into BrowserActivity without leaving a second overlay window behind.
                assertTrue(requireNotNull(node("Open fullscreen")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await {
                    automation.waitForIdle(50, 500)
                    node("Address and search") != null && main { BubbleService.active == null }
                }
                assertEquals(before, selectedId())

                // And shrink the *same* BrowserActivity back to the saved floating geometry again,
                // proving the round trip survives the real GeckoSession surface ownership handoff.
                scenario.onActivity { it.collapse(FloatingMode.CHAT) }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }
                await { node(chatPill) != null }
                assertEquals(before, selectedId())

                // DOWN = existing minimize behavior after the matched round trip.
                swipe(node(chatPill)!!, 0f, 44f)
                await { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE && BubbleService.active?.window?.isTransitioning == false } }
            }
        } finally {
            FullscreenHandoff.cancelAll()
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close(); worker.join(1000)
        }
    }

    @Test fun chooserMovesLegacyFooterActionsIntoTopMenuAndHasBottomResizeBar() {
        val oldFlags = automation.serviceInfo.flags
        try {
            prepareOverlayAccess()
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await { main { Workspace.peek()?.ready == true } }
                scenario.onActivity { activity ->
                    AccessPreferences.get(activity).update(AccessPreferences.get(activity).options.copy(enabled = false))
                    activity.collapse(FloatingMode.BUBBLE)
                }
                await { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } }
                instrumentation.runOnMainSync { BubbleService.active?.window?.showChooser() }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHOOSER && BubbleService.active?.window?.isTransitioning == false } }
                val chooserPill = "Swipe up for last tab or down to minimize floating window"
                await {
                    automation.waitForIdle(50, 500)
                    node("Workspace menu") != null && node("Resize conversation chooser") != null && node(chooserPill) != null
                }
                assertNotNull(node("Workspace menu"))
                assertNotNull(node("Resize conversation chooser"))
                assertNotNull(node(chooserPill))
                assertNull("Legacy Chat tools footer must be removed", textNode("Chat tools"))
                assertNull("Legacy Edge access footer must be removed", textNode("Edge access"))
                assertNull("Legacy Reply sound footer must be removed", textNode("Reply sound"))

                val menu = requireNotNull(node("Workspace menu"))
                assertTrue(menu.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await {
                    automation.waitForIdle(50, 500)
                    textNode("Chat tools") != null && textNode("Edge access") != null &&
                        textNode("Reply sound / ChatGPT notifications") != null
                }
            }
        } finally {
            FullscreenHandoff.cancelAll()
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }

    private fun swipe(node: AccessibilityNodeInfo, dxDp: Float, dyDp: Float) {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        val dx = dxDp * context.resources.displayMetrics.density
        val dy = dyDp * context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, x, y)
        event(down, MotionEvent.ACTION_MOVE, x + dx * .52f, y + dy * .52f)
        event(down, MotionEvent.ACTION_MOVE, x + dx, y + dy)
        event(down, MotionEvent.ACTION_UP, x + dx, y + dy)
    }
    private fun prepareOverlayAccess() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }
    private fun node(description: String): AccessibilityNodeInfo? = findNode { n -> n.contentDescription?.toString() == description }
    private fun textNode(text: String): AccessibilityNodeInfo? = findNode { n -> n.text?.toString() == text }
    private fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && match(n)) return n
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
    private fun selectedId(): String { var result = ""; instrumentation.runOnMainSync { result = Workspace.peek()?.selectedId.orEmpty() }; return result }
    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        fail("Floating chrome runtime condition timed out")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
