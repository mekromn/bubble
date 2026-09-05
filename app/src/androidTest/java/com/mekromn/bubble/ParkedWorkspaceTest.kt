package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

/** Synthetic pages only. Real touch drags and real SystemUI notification taps exercise the route. */
@RunWith(AndroidJUnit4::class)
class ParkedWorkspaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    @Test fun draggingToHideAndNotificationRestorePreserveTheSessionAndPlacement() {
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>Park test</title><body style="background:#14263d;color:#eef4ff;font:20px sans-serif"><h1>Persistent floating workspace</h1><p>Drag to hide. Restore without a reload.</p><script>let n=0;setInterval(()=>document.title='PARK-'+(++n),250)</script>""".toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(html)
            }} catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val oldFlags = automation.serviceInfo.flags
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                await { main { Workspace.peek()?.selected?.painted == true && Workspace.peek()?.selected?.title?.startsWith("PARK-") == true } }
                var original: GeckoSession? = null; var tabId = ""; var targetX = 0f; var targetY = 0f
                scenario.onActivity { activity ->
                    original = activity.selectedSession; tabId = activity.workspace.selectedId
                    activity.workspace.tabs.map { it.id }.filter { it != tabId }.forEach(activity.workspace::close)
                    val metrics = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics
                    val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    targetX = metrics.bounds.width() / 2f
                    targetY = metrics.bounds.height() - inset.bottom - 60f * activity.resources.displayMetrics.density
                }
                shell("input keyevent KEYCODE_HOME")
                await { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE } }
                Thread.sleep(300)
                var initial = WindowBox(0,0,1,1)
                instrumentation.runOnMainSync { initial = BubbleService.active!!.window!!.box }
                val x = initial.x + initial.width / 2f; val y = initial.y + initial.height / 2f
                var time = SystemClock.uptimeMillis()
                event(time, MotionEvent.ACTION_DOWN, x, y)
                event(time, MotionEvent.ACTION_MOVE, x - 45, y + 45)
                await { main { BubbleService.active?.window?.dismissTargetAttached == true } }
                screenshot("v061-drag-hide-target.png")
                event(time, MotionEvent.ACTION_CANCEL, x - 45, y + 45)
                await { main { BubbleService.active?.window?.dismissTargetAttached == false } }
                assertTrue(main { BubbleService.active?.isParked == false })
                // Cancellation preserves the resting placement and never hides the workspace.
                assertTrue(main { BubbleService.active?.window?.box == initial })
                time = SystemClock.uptimeMillis()
                event(time, MotionEvent.ACTION_DOWN, x, y)
                for (step in 1..12) {
                    val p = step / 12f
                    event(time, MotionEvent.ACTION_MOVE, x + (targetX - x) * p, y + (targetY - y) * p)
                    Thread.sleep(20)
                }
                Thread.sleep(100)
                screenshot("v061-drag-armed.png")
                event(time, MotionEvent.ACTION_UP, targetX, targetY)
                await { main { BubbleService.active?.isParked == true && BubbleService.active?.window == null } }
                assertTrue(main { Workspace.peek()?.selected?.session === original })
                val notes = context.getSystemService(NotificationManager::class.java)
                assertTrue(notes.activeNotifications.any { it.id == BubbleService.NOTICE_ID && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("hidden") == true })
                shell("cmd statusbar expand-notifications")
                tapText("Bubble hidden · tap to restore")
                await { main { BubbleService.active?.window?.mode == FloatingMode.BUBBLE && BubbleService.active?.isParked == false } }
                assertTrue(main { BubbleService.active?.window?.box == initial && Workspace.peek()?.selected?.session === original })
                screenshot("v061-notification-restored-bubble.png")
                // Exercise the Android alert layer; lifecycle detection is independently tested
                // against DOM fixtures in tools/test-monitor.cjs (no real ChatGPT prompt is sent).
                instrumentation.runOnMainSync {
                    assertTrue(BubbleService.active!!.park())
                    Workspace.peek()!!.selected!!.unread = true
                    Replies.finished(context, tabId)
                }
                await { notes.activeNotifications.any { it.tag == tabId && it.id == 2 } }
                val reply = notes.activeNotifications.first { it.tag == tabId && it.id == 2 }.notification
                assertEquals(Notification.CATEGORY_MESSAGE, reply.category)
                assertEquals(Notification.VISIBILITY_PRIVATE, reply.visibility)
                shell("cmd statusbar expand-notifications")
                screenshot("v061-reply-notification.png")
                tapText("Your ChatGPT reply is ready")
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT && BubbleService.active?.window?.geckoView?.session === original } }
                await { main { BubbleService.active?.window?.isTransitioning == false } }
                assertFalse(notes.activeNotifications.any { it.tag == tabId && it.id == 2 })
                screenshot("v061-reply-opened-floating.png")
                File(folder(), "v061-parking-result.txt").writeText("Cancelled drag preserved placement. Drag-to-target hid both windows. Real notification tap restored the original placement and same GeckoSession. Reply notification tap opened the exact floating session and cleared unread.\n")
            }
        } finally {
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("cmd statusbar collapse")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close(); worker.join(1000)
        }
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(automation.injectInputEvent(e, true)) } finally { e.recycle() }
    }
    private fun tapText(text: String) {
        var found: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || found != null) return
            if (node.isVisibleToUser && node.text?.toString() == text) { found = node; return }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        await { found = null; automation.windows.forEach { visit(it.root) }; found != null }
        val r = Rect(); found!!.getBoundsInScreen(r)
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY()); Thread.sleep(60)
        event(down, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
    }
    private fun main(predicate: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = predicate() }; return result }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        screenshot("v061-parking-failure.png"); fail("Park/restore interaction timed out")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun folder() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun screenshot(name: String) { automation.takeScreenshot()?.let { image -> File(folder(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
}
