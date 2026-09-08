package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

                scenario.onActivity { it.collapse(FloatingMode.CHAT) }
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }

                // Headless SwiftShader's UiAutomation screenshot does not contain Gecko SurfaceView
                // pixels even before a transition (proved by build 55), so it cannot be the visual
                // oracle for this renderer. Validate the actual intermediate source that the user
                // sees instead: PixelCopy/capturePixels must have frozen the green webpage itself,
                // not a View.draw() frame with a black SurfaceView hole.
                await {
                    val debug = FullscreenHandoff.debugSummary()
                    debug.contains("frame=") && !debug.contains("frame=0x0")
                }
                val debug = FullscreenHandoff.debugSummary()
                val rgb = Regex("center=(\\d+),(\\d+),(\\d+)").find(debug)?.groupValues
                assertNotNull("Shrink capture diagnostics missing center pixel: $debug", rgb)
                val r = rgb!![1].toInt()
                val g = rgb[2].toInt()
                val b = rgb[3].toInt()
                assertTrue(
                    "Frozen fullscreen source did not contain the painted webpage: $debug",
                    g >= 145 && g > r * 1.65f && g > b * 1.25f
                )

                // The handoff must really attach and start the dedicated screenshot layer; merely
                // ending up with a floating card is not enough to pass this current-feature gate.
                await {
                    val state = FullscreenHandoff.debugSummary()
                    state.contains("overlayAttached=true") && state.contains("morphStarted=true")
                }

                // The real floating card must finish fully visible at its saved geometry after the
                // frozen screenshot cross-fades away.
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
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

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