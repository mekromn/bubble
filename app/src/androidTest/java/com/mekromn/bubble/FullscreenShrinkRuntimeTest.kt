package com.mekromn.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime gate for the currently-developed fullscreen -> floating screenshot morph only. */
@RunWith(AndroidJUnit4::class)
class FullscreenShrinkRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun fullscreenSnapshotStretchesIntoFloatingCardWithoutVisibilityGap() {
        val oldFlags = automation.serviceInfo.flags
        val server = ServerSocket(0)
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>SHRINK-FIXTURE</title><style>html,body{margin:0;min-height:100%;background:rgb(0,200,83)!important;color:#071b10;font:22px sans-serif}h1{padding:36px;margin:0}</style><h1>SHRINK-FIXTURE</h1>""".toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(html)
                }
            } catch (_: Exception) {
                if (server.isClosed) break
            }
        }.apply { isDaemon = true; start() }

        try {
            prepareOverlayAccess()
            assertTrue("Test setup failed to grant overlay access", Settings.canDrawOverlays(context))
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                assertEquals("Test setup failed to grant notifications", PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
            val url = "http://127.0.0.1:${server.localPort}/shrink"
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                await { main { Workspace.peek()?.ready == true } }
                scenario.onActivity { activity ->
                    AccessPreferences.get(activity).update(
                        AccessPreferences.get(activity).options.copy(enabled = false)
                    )
                    val id = activity.workspace.create(url).id
                    activity.workspace.tabs.map { it.id }.filter { it != id }.forEach(activity.workspace::close)
                }
                await {
                    main {
                        Workspace.peek()?.selected?.title == "SHRINK-FIXTURE" &&
                            Workspace.peek()?.selected?.painted == true
                    }
                }
                // ActivityScenario can report a painted Gecko frame one choreography turn before
                // BrowserActivity's root itself is marked laid out. The real collapse gesture cannot
                // occur before a visible UI exists, so make the synthetic runtime gesture honor the
                // same prerequisite instead of racing FullscreenHandoff's source capture.
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        val root = browserRoot(activity)
                        ready = root.isAttachedToWindow && root.isLaidOut && root.width > 0 && root.height > 0 &&
                            activity.geckoView.isAttachedToWindow && activity.geckoView.isLaidOut &&
                            activity.geckoView.width > 0 && activity.geckoView.height > 0
                    }
                    ready
                }

                scenario.onActivity { activity ->
                    val root = browserRoot(activity)
                    root.postOnAnimation {
                        if (!activity.isFinishing) activity.collapse(FloatingMode.CHAT)
                    }
                }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }

                // SwiftShader's headless SurfaceView/capturePixels path does not expose Gecko's
                // painted compositor buffer, even before any transition. The Pixel recording proved
                // the physical device does. Therefore CI validates the mechanics it can observe:
                // a real frozen frame is allocated, the dedicated overlay attaches, and the morph
                // actually starts. Visual pixel fidelity remains a physical-device gate.
                await {
                    val debug = FullscreenHandoff.debugSummary()
                    debug.contains("frame=") && !debug.contains("frame=0x0")
                }
                await {
                    val state = FullscreenHandoff.debugSummary()
                    state.contains("overlayAttached=true") && state.contains("morphStarted=true")
                }

                // The live card must finish fully visible and keep the same workspace/session after
                // the stationary endpoint dissolve removes the frozen screenshot.
                await {
                    main {
                        val window = BubbleService.active?.window
                        window?.mode == FloatingMode.CHAT &&
                            window.transitionView.alpha >= .99f &&
                            window.transitionView.isShown &&
                            window.box.width > 0 && window.box.height > 0
                    }
                }
                assertEquals("SHRINK-FIXTURE", mainValue { Workspace.peek()?.selected?.title.orEmpty() })
            }
        } finally {
            FullscreenHandoff.cancelAll()
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            server.close()
            worker.join(1000)
        }
    }

    private fun prepareOverlayAccess() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
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

    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            if (test()) return
            Thread.sleep(80)
        }
        fail("Fullscreen shrink runtime condition timed out; ${FullscreenHandoff.debugSummary()}")
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
