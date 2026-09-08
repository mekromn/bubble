package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
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

                // Validate the observation path itself before judging the transition. If the headless
                // emulator cannot see Gecko's live SurfaceView in a normal screenshot, a zero-valued
                // in-flight screenshot cannot be used as evidence that the morph itself went black.
                val baselineScreenshot = requireNotNull(automation.takeScreenshot())
                val baselineRatio = try { greenRatio(baselineScreenshot) } finally { baselineScreenshot.recycle() }
                assertTrue(
                    "Runtime screenshot cannot observe the painted baseline Gecko page; baseline=$baselineRatio",
                    baselineRatio >= .010f
                )

                scenario.onActivity { it.collapse(FloatingMode.CHAT) }

                // Service readiness happens before the screenshot starts moving. Sample shortly after
                // that point so the emulator validates the in-flight object, not merely the final UI.
                await { main { BubbleService.active?.window?.mode == FloatingMode.CHAT } }
                Thread.sleep(105)
                val screenshot = requireNotNull(automation.takeScreenshot())
                val ratio = try { greenRatio(screenshot) } finally { screenshot.recycle() }
                assertTrue(
                    "Fullscreen -> floating morph lost the browser picture in flight; baseline=$baselineRatio " +
                        "fixture=$ratio ${FullscreenHandoff.debugSummary()}",
                    ratio >= .010f
                )

                // The real floating card must finish fully visible at its saved geometry after the
                // frozen screenshot cross-fades away.
                await {
                    main {
                        val window = BubbleService.active?.window
                        window?.mode == FloatingMode.CHAT &&
                            window.transitionView.alpha >= .99f &&
                            window.transitionView.isShown
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

    private fun greenRatio(bitmap: Bitmap): Float {
        val step = maxOf(4, minOf(bitmap.width, bitmap.height) / 180)
        var matching = 0
        var total = 0
        var y = step / 2
        while (y < bitmap.height) {
            var x = step / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (g >= 145 && g > r * 1.65f && g > b * 1.25f) matching++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else matching.toFloat() / total
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
        fail("Fullscreen shrink runtime condition timed out")
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}