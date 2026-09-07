package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageAppearanceRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun forceDarkAndLightAreIndependentPerLogicalTab() {
        val server = ServerSocket(0)
        val html = """<!doctype html><meta name="viewport" content="width=device-width"><style>html,body{background:#fff;color:#111}</style><title>WAIT</title><p>appearance fixture</p><script>
          function bubbleMode(){const c=document.documentElement.classList;if(c.contains('bubble-force-dark'))return 'FORCE-DARK';if(c.contains('bubble-force-light'))return 'FORCE-LIGHT';return 'DEFAULT';}
          const update=()=>document.title=bubbleMode(); new MutationObserver(update).observe(document.documentElement,{attributes:true,attributeFilter:['class']}); update();
        </script>""".toByteArray()
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(html)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}/appearance"
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java).setData(Uri.parse(url))).use { scenario ->
                await { main { Workspace.peek()?.ready == true && Workspace.peek()?.selected?.painted == true } }
                var darkId = ""
                scenario.onActivity { activity ->
                    darkId = activity.workspace.selectedId
                    context.getSharedPreferences("bubble-page-appearance-v1", 0).edit().putString(darkId, "dark").commit()
                    activity.workspace.selected!!.session!!.reload()
                }
                await { main { Workspace.peek()?.selectedId == darkId && Workspace.peek()?.selected?.title == "FORCE-DARK" } }

                var lightId = ""
                scenario.onActivity { activity ->
                    val tab = ChatTab(url = url, profileId = activity.workspace.selected!!.profileId)
                    lightId = tab.id
                    context.getSharedPreferences("bubble-page-appearance-v1", 0).edit().putString(lightId, "light").commit()
                    activity.workspace.tabs += tab
                    activity.workspace.select(lightId)
                }
                await { main { Workspace.peek()?.selectedId == lightId && Workspace.peek()?.selected?.title == "FORCE-LIGHT" } }

                scenario.onActivity { it.workspace.select(darkId) }
                await { main { Workspace.peek()?.selectedId == darkId } }
                scenario.onActivity {
                    assertEquals(PageAppearanceMode.DARK, PageAppearance.mode(it, darkId))
                    assertEquals(PageAppearanceMode.LIGHT, PageAppearance.mode(it, lightId))
                }
            }
        } finally {
            server.close(); worker.join(1000)
            context.getSharedPreferences("bubble-page-appearance-v1", 0).edit().clear().commit()
        }
    }

    private fun main(test: () -> Boolean): Boolean { var result=false; instrumentation.runOnMainSync { result=test() }; return result }
    private fun await(test: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+45_000
        while(SystemClock.elapsedRealtime()<end) { if(test()) return; Thread.sleep(100) }
        assertTrue("Per-tab appearance runtime condition timed out", false)
    }
}
