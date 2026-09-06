package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RebuildRegressionTest {
    @Test fun continuousMetadataChangesDoNotStarveDurableCheckpoints() = withPage { scenario ->
        val prefix = "checkpoint-${UUID.randomUUID()}-"
        var id = ""
        scenario.onActivity { id = it.workspace.selectedId }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "workspace-v2.json")
        var savedDuringChurn = false
        val end = SystemClock.elapsedRealtime() + 5000
        var sequence = 0
        // There is never a 500ms idle gap. The previous trailing debounce could not pass this.
        while (!savedDuringChurn && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity {
                it.workspace.selected!!.title = prefix + sequence++
                it.workspace.changed(true)
            }
            Thread.sleep(70)
            // Read only the committed base file. AtomicFile.openRead() may perform recovery
            // writes, so it must not race an active writer from an independent test reader.
            savedDuringChurn = runCatching {
                val data = JSONObject(file.readText())
                val tabs = data.getJSONArray("tabs")
                (0 until tabs.length()).any { index ->
                    val item = tabs.getJSONObject(index)
                    item.getString("id") == id && item.getString("title").startsWith(prefix)
                }
            }.getOrDefault(false)
        }
        assertTrue("Continuous page updates postponed every disk checkpoint", savedDuringChurn)
    }

    @Test fun darkWebPreferenceAndBadSessionSnapshotFallBackToRealNavigation() = withPage { scenario ->
        waitFor(scenario) { it.painted && it.pageTitle == "DARK-PREFERENCE" }
        var id = ""
        scenario.onActivity { activity ->
            val tab = ChatTab(url = activity.workspace.selected!!.url).apply {
                savedState = "this is not a serialized Gecko session"
            }
            id = tab.id
            activity.workspace.tabs += tab
            activity.workspace.select(id)
        }
        waitFor(scenario) { it.workspace.selectedId == id && it.painted && it.pageTitle == "DARK-PREFERENCE" }
        scenario.onActivity { assertFalse(it.isFinishing); assertNull(it.workspace.selected!!.error) }
    }

    @Test fun reversingNativeTrayAnimationNeverHidesItOrRetainsHardwareLayer() = withPage { scenario ->
        var tray: TabTray? = null
        scenario.onActivity { activity ->
            activity.showTabs(true)
            tray = findTray(activity.window.decorView)
        }
        assertNotNull(tray)
        waitFor(scenario) { tray!!.isShown && tray!!.alpha >= 0.99f && tray!!.layerType == View.LAYER_TYPE_NONE }
        scenario.onActivity { it.showTabs(false) }
        Thread.sleep(40)
        scenario.onActivity { it.showTabs(true) }
        waitFor(scenario) { tray!!.isShown && tray!!.alpha >= 0.99f && tray!!.translationY == 0f && tray!!.layerType == View.LAYER_TYPE_NONE }
        scenario.onActivity {
            assertEquals("Never cache the Gecko compositor as a UI animation layer", View.LAYER_TYPE_NONE, it.geckoView.layerType)
            it.showTabs(false)
        }
        waitFor(scenario) { tray!!.visibility == View.GONE && tray!!.layerType == View.LAYER_TYPE_NONE }
    }

    @Test fun compactBrowserBarHasRefreshShareAndFloatingBeforeTabs() = withPage { scenario ->
        // ActivityScenario can report the Activity ready slightly before the first measured layout
        // and accessibility pass. Wait until every requested control exists and is laid out.
        waitFor(scenario) { activity ->
            val root = activity.window.decorView
            listOf("Refresh page", "Share page", "Open interactive floating chat", "Workspace tabs", "Browser menu")
                .map { findControl(root, it) }
                .all { it != null && it.isLaidOut && it.width > 0 && it.height > 0 }
        }
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val refresh = requireNotNull(findControl(root, "Refresh page"))
            val share = requireNotNull(findControl(root, "Share page"))
            val floating = requireNotNull(findControl(root, "Open interactive floating chat"))
            val tabs = requireNotNull(findControl(root, "Workspace tabs"))
            val menu = requireNotNull(findControl(root, "Browser menu"))
            fun x(view: View): Int { val p = IntArray(2); view.getLocationOnScreen(p); return p[0] }
            assertTrue("Refresh should be before Share", x(refresh) < x(share))
            assertTrue("Share should stay near the floating control", x(share) < x(floating))
            assertTrue("Requested control swap regressed: floating must be before tab counter", x(floating) < x(tabs))
            assertTrue("Menu remains the final compact action", x(tabs) < x(menu))
        }
    }

    private fun findControl(view: View, description: String): View? {
        val actual = view.contentDescription?.toString()
        val matches = actual == description || (description == "Workspace tabs" && actual?.startsWith("Workspace tabs") == true)
        if (view.visibility == View.VISIBLE && matches) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findControl(view.getChildAt(i), description)?.let { return it }
        return null
    }
    private fun findTray(view: View): TabTray? {
        if (view is TabTray) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findTray(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun waitFor(scenario: ActivityScenario<BrowserActivity>, predicate: (BrowserActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        var success = false
        while (!success && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { success = predicate(it) }
            if (!success) Thread.sleep(100)
        }
        assertTrue("Rebuild regression condition timed out", success)
    }
    private fun withPage(test: (ActivityScenario<BrowserActivity>) -> Unit) {
        val server = ServerSocket(0)
        val html = """<!doctype html><meta name="viewport" content="width=device-width"><title>LOADING</title><style>body{background:#14253b;color:white;font:22px sans-serif}</style><h1>Bubble regression fixture</h1><p>Native session and disk validation</p><script>document.title=matchMedia('(prefers-color-scheme:dark)').matches?'DARK-PREFERENCE':'LIGHT-PREFERENCE';</script>""".toByteArray()
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)
                .setData(Uri.parse("http://127.0.0.1:${server.localPort}/"))).use { scenario ->
                waitFor(scenario) { it.painted && !it.workspace.selected!!.loading }
                test(scenario)
            }
        } finally { server.close(); worker.join(1000) }
    }
}
