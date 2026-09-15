package com.mekromn.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hard smoke gate for the exact fullscreen -> expanded Activity-hosted floating transition.
 *
 * This intentionally drives BrowserActivity.collapse(CHAT), not a direct Activity launch. A pass
 * means the foreground service starts, FloatingBrowserActivity is created/resumed, its one ViewRoot
 * and direct Gecko host are alive, the same selected session transfers, and the process survives
 * beyond the transition instead of crashing during window attachment.
 */
@RunWith(AndroidJUnit4::class)
class ActivityFloatingTransitionSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun fullscreenToFloatingActivitySurvivesAndOwnsSelectedSession() {
        val oldFlags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>FLOAT-SMOKE</title><style>html,body{margin:0;min-height:100%;background:#111;color:white;font:22px sans-serif}p{padding:32px}</style><p>fullscreen to floating activity smoke</p>""".toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(html)
                }
            } catch (_: Exception) {
                if (server.isClosed) break
            }
        }.apply { isDaemon = true; start() }

        try {
            prepareOverlayAccess()
            assertTrue("overlay app-op was not granted", Settings.canDrawOverlays(context))
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                assertEquals(PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }

            val url = "http://127.0.0.1:${server.localPort}/float-smoke"
            ActivityScenario.launch<BrowserActivity>(
                Intent(context, BrowserActivity::class.java).setData(android.net.Uri.parse(url))
            ).use { scenario ->
                await("workspace ready") { main { Workspace.peek()?.ready == true } }
                await("fixture painted") {
                    main {
                        Workspace.peek()?.selected?.title == "FLOAT-SMOKE" &&
                            Workspace.peek()?.selected?.painted == true
                    }
                }
                await("fullscreen root ready") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val root = browserRoot(activity)
                        ready = root.isAttachedToWindow && root.isLaidOut && root.width > 0 && root.height > 0 &&
                            activity.geckoView.isAttachedToWindow && activity.geckoView.isLaidOut &&
                            activity.geckoView.width > 0 && activity.geckoView.height > 0
                    }
                    ready
                }

                val selectedSession = mainValue { Workspace.peek()!!.selected!!.session }
                assertNotNull("fixture has no GeckoSession", selectedSession)

                scenario.onActivity { activity ->
                    activity.collapse(FloatingMode.CHAT)
                }

                await("FloatingBrowserActivity RESUMED") {
                    resumedFloating() != null
                }
                await("floating root and Gecko attached") {
                    val host = resumedFloating() ?: return@await false
                    main {
                        !host.isFinishing &&
                            host.currentMode == FloatingMode.CHAT &&
                            host.transitionView.isAttachedToWindow &&
                            host.transitionView.isLaidOut &&
                            host.transitionView.width > 0 && host.transitionView.height > 0 &&
                            host.geckoView?.isAttachedToWindow == true &&
                            host.geckoView?.session === selectedSession &&
                            Workspace.peek()?.floatingVisible == true
                    }
                }

                // The reported physical failure is immediate on entering windowed mode. Keep the
                // Activity alive across multiple frames/seconds so an attach/lifecycle crash cannot
                // masquerade as a successful launch.
                Thread.sleep(3000)
                val host = resumedFloating()
                assertNotNull("floating Activity disappeared after transition", host)
                assertTrue("floating Activity is finishing", mainValue { !host!!.isFinishing })
                assertSame("selected GeckoSession did not survive fullscreen -> floating",
                    selectedSession, mainValue { host!!.geckoView?.session })
                assertEquals("FLOAT-SMOKE", mainValue { Workspace.peek()?.selected?.title })
            }
        } finally {
            FullscreenHandoff.cancelAll()
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("am force-stop ${context.packageName}")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close()
            worker.join(1000)
        }
    }

    private fun resumedFloating(): FloatingBrowserActivity? {
        var result: FloatingBrowserActivity? = null
        instrumentation.runOnMainSync {
            val resumed: Collection<Activity> = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
            result = resumed.filterIsInstance<FloatingBrowserActivity>().firstOrNull()
        }
        return result
    }

    private fun prepareOverlayAccess() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33)
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val end = SystemClock.elapsedRealtime() + 5000
        while (!Settings.canDrawOverlays(context) && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun browserRoot(activity: BrowserActivity): View =
        BrowserActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as View

    private fun main(test: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = test() }
        return result
    }

    private fun <T> mainValue(value: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = value() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            if (test()) return
            Thread.sleep(80)
        }
        fail("Timed out waiting for $label; ${FullscreenHandoff.debugSummary()}")
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
