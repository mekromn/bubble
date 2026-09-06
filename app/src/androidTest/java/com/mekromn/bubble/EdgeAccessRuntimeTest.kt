package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

@RunWith(AndroidJUnit4::class)
class EdgeAccessRuntimeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val automation = ins.uiAutomation
    @Test fun longPressHidesBothModesAndEdgeGesturesRestoreTheSameChat() {
        val flags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val r = socket.getInputStream().bufferedReader()
                while (!r.readLine().isNullOrEmpty()) { }
                val bytes = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>EDGE-LIVE</title><body style="background:#151515;color:white;font:22px sans-serif"><h1>Edge access test</h1><input aria-label="Test composer"><p>This session must survive every hide and restore.</p>""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(bytes)
            } } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await("Preferences and workspace ready") { main { Workspace.peek()?.ready == true && AccessPreferences.get(context).ready } }
                scenario.onActivity { a ->
                    AccessPreferences.get(a).update(EdgeOptions())
                    val id = a.workspace.create("http://127.0.0.1:${server.localPort}/").id
                    a.workspace.tabs.map { it.id }.filter { it != id }.forEach(a.workspace::close)
                }
                await("Fixture paints") { main { Workspace.peek()?.selected?.title == "EDGE-LIVE" && Workspace.peek()?.selected?.painted == true } }
                var session: GeckoSession? = null
                scenario.onActivity { session = it.selectedSession }
                press("Open interactive floating chat", true)
                await("Fullscreen long press parks without any control") { main { BubbleService.active?.isParked == true && BubbleService.active?.edge == null && BubbleService.active?.window == null } }
                assertSame(session, currentSession())
                restoreNotification(scenario)
                await("Notification restores bubble") { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } }
                tapMatch { it.contentDescription?.startsWith("Choose a conversation,") == true }
                tapMatch { it.contentDescription?.startsWith("EDGE-LIVE,") == true }
                await("Floating chat restored") { main { BubbleService.active?.window?.geckoView?.session === session && BubbleService.active?.window?.isTransitioning == false } }
                press("Minimize floating window", true)
                await("Floating long press parks") { main { BubbleService.active?.isParked == true && BubbleService.active?.window == null } }
                ins.runOnMainSync { AccessPreferences.get(context).update(EdgeOptions(enabled = true, left = false)) }
                assertTrue("Preference change unexpectedly restored UI", main { BubbleService.active?.window == null && BubbleService.active?.edge == null })
                restoreNotification(scenario)
                await("Restoration installs only the edge") { main { BubbleService.active?.edge != null && BubbleService.active?.window == null } }
                screenshot("v071-right-edge.png")
                var box = WindowBox(0,0,1,1)
                ins.runOnMainSync { box = BubbleService.active!!.edge!!.box }
                val x = box.x + box.width / 2f; val y = box.y + box.height / 2f
                val distance = 70f * context.resources.displayMetrics.density
                swipe(x, y, x - distance, y, true)
                assertTrue("Cancelled edge swipe opened a window", main { BubbleService.active?.edge != null && BubbleService.active?.window == null })
                swipe(x, y, x, y + distance, false)
                assertTrue("Vertical edge drag opened a window", main { BubbleService.active?.edge != null && BubbleService.active?.window == null })
                swipe(x, y, x - distance, y, false)
                await("Inward swipe opens chooser") { main { BubbleService.active?.window?.mode == FloatingMode.CHOOSER && BubbleService.active?.edge == null } }
                assertNotEquals("Edge gesture promoted fullscreen", Lifecycle.State.RESUMED, scenario.state)
                screenshot("v071-edge-chooser.png")
                tapMatch { it.contentDescription?.startsWith("EDGE-LIVE,") == true }
                await("Same floating chat from edge") { main { BubbleService.active?.window?.geckoView?.session === session && BubbleService.active?.window?.isTransitioning == false } }
                press("Minimize floating window", false)
                await("Normal minimize returns to edge") { main { BubbleService.active?.edge != null && BubbleService.active?.window == null } }
                ins.runOnMainSync { AccessPreferences.get(context).update(EdgeOptions(enabled = true, left = true, indicator = false)) }
                await("New edge geometry applied") { main { BubbleService.active?.edge?.box?.x == 0 } }
                ins.runOnMainSync { box = BubbleService.active!!.edge!!.box }
                swipe(box.x + box.width / 2f, box.y + box.height / 2f, box.x + box.width / 2f + distance, box.y + box.height / 2f, false)
                await("Invisible left edge restores chooser") { main { BubbleService.active?.window?.mode == FloatingMode.CHOOSER } }
                press("Minimize floating window", true)
                await("Hide removes invisible edge too") { main { BubbleService.active?.isParked == true && BubbleService.active?.edge == null && BubbleService.active?.window == null } }
                val note = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == BubbleService.NOTICE_ID }.notification
                assertTrue("Missing recovery action", note.actions.any { it.title.toString() == "Use bubble instead" })
                assertSame(session, currentSession())
                screenshot("v071-parked-final.png")
                File(folder(), "v071-results.txt").writeText("Real long presses parked fullscreen and floating chat. SystemUI notification taps restored bubble then edge. Cancelled and vertical edge gestures did not open. Right and invisible left inward swipes opened chooser without fullscreen; same GeckoSession preserved. Normal minimize returned to edge; notification-only hide removed both surfaces. Recovery action present.\n")
            }
        } finally {
            ins.runOnMainSync { AccessPreferences.get(context).takeIf { it.ready }?.update(EdgeOptions()); QuickPanel.dismiss() }
            context.stopService(Intent(context, BubbleService::class.java)); shell("cmd statusbar collapse")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            server.close(); worker.join(1000)
        }
    }
    private fun currentSession(): GeckoSession? { var s: GeckoSession? = null; ins.runOnMainSync { s = Workspace.peek()?.selected?.session }; return s }
    private fun restoreNotification(scenario: ActivityScenario<BrowserActivity>) {
        // park() updates service state before its ResultReceiver has finished backgrounding
        // BrowserActivity. Opening SystemUI in that interval races the task transition.
        // Observe the real user-driven transition; do NOT force lifecycle state in the test.
        await("Hide acknowledged and browser actually stopped") {
            scenario.state == Lifecycle.State.CREATED && main {
                BubbleService.active?.isParked == true && Workspace.peek()?.visible == false
            }
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        await("Restore notification actually posted") {
            notifications.activeNotifications.any {
                it.id == BubbleService.NOTICE_ID &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Bubble hidden · tap to restore"
            }
        }
        ins.waitForIdleSync()
        shell("cmd statusbar expand-notifications")
        await("SystemUI exposes restore notification") {
            find { it.packageName?.toString() == "com.android.systemui" &&
                it.text?.toString() == "Bubble hidden · tap to restore" } != null
        }
        screenshot("v072-before-notification-restore.png")
        tapMatch { it.packageName?.toString() == "com.android.systemui" &&
            it.text?.toString() == "Bubble hidden · tap to restore" }
    }
    private fun press(label: String, long: Boolean) = tapMatch(long) { it.contentDescription?.toString() == label }
    private fun tapMatch(long: Boolean = false, test: (AccessibilityNodeInfo) -> Boolean) {
        var found: AccessibilityNodeInfo? = null
        await("Control available") { found = find(test); found != null }
        val rect = Rect(); found!!.getBoundsInScreen(rect)
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        Thread.sleep(if (long) 1400 else 80)
        event(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
    }
    private fun swipe(x: Float, y: Float, endX: Float, endY: Float, cancel: Boolean) {
        val down = SystemClock.uptimeMillis(); event(down, MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..8) { event(down, MotionEvent.ACTION_MOVE, x + (endX - x)*i/8, y + (endY-y)*i/8); Thread.sleep(25) }
        event(down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, endY)
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(automation.injectInputEvent(e, true)) } finally { e.recycle() }
    }
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && test(n)) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        automation.windows.sortedByDescending { it.layer }.forEach { walk(it.root)?.let { n -> return n } }; return null
    }
    private fun main(test: () -> Boolean): Boolean { var result = false; ins.runOnMainSync { result = test() }; return result }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        screenshot("v071-failure.png")
        val diagnostic = StringBuilder(message).append('\n')
        main {
            diagnostic.append("workspaceVisible=").append(Workspace.peek()?.visible)
                .append(" parked=").append(BubbleService.active?.isParked).append('\n')
            true
        }
        context.getSystemService(NotificationManager::class.java).activeNotifications.forEach {
            diagnostic.append("notification=").append(it.id).append(" title=")
                .append(it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)).append('\n')
        }
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 30) return
            diagnostic.append(" ".repeat(depth)).append(node.packageName).append(' ')
                .append(node.className).append(" visible=").append(node.isVisibleToUser)
                .append(" text=").append(node.text?.take(160)).append('\n')
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        automation.windows.forEach { describe(it.root) }
        // Disposable emulator only; pages contain synthetic content, never a user's account.
        File(folder(), "v072-edge-failure-state.txt").writeText(diagnostic.toString())
        fail(message)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun folder() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun screenshot(name: String) { automation.takeScreenshot()?.let { image -> File(folder(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
}
