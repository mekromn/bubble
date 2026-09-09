package com.mekromn.bubble

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Focused runtime proof for the September 8 UI/share/Vault pass. */
@RunWith(AndroidJUnit4::class)
class UiVaultRuntimeTest {
    @Test fun tabIconSwipeUpOpensFullscreenGlassYourChatsAndRefreshTracksLoading() = withBrowser { scenario ->
        waitFor(scenario) { activity ->
            findControl(activity.window.decorView, "Workspace tabs")?.let {
                it.isAttachedToWindow && it.isLaidOut && it.width > 0 && it.height > 0
            } == true
        }
        scenario.onActivity { activity ->
            val tabs = requireNotNull(findControl(activity.window.decorView, "Workspace tabs"))
            val x = tabs.width / 2f
            val y = tabs.height / 2f
            val now = SystemClock.uptimeMillis()
            tabs.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
            tabs.dispatchTouchEvent(MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_MOVE, x, y - 90f, 0))
            tabs.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y - 90f, 0))
        }
        waitFor(scenario) { tabTray(it)?.isShowing == true }
        scenario.onActivity { activity ->
            val tray = requireNotNull(tabTray(activity))
            val win = requireNotNull(tray.window)
            assertEquals(0, win.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertNotNull(win.decorView.background)
            assertTrue(win.decorView.isShown)
            activity.showTabs(false)
        }
        waitFor(scenario) { tabTray(it)?.isShowing != true }

        scenario.onActivity { activity ->
            val refresh = requireNotNull(findControl(activity.window.decorView, "Refresh page"))
            activity.workspace.selected!!.loading = true
            activity.workspace.changed()
            assertTrue(refresh.isAttachedToWindow)
        }
        waitFor(scenario) { activity ->
            val refresh = findControl(activity.window.decorView, "Refresh page") ?: return@waitFor false
            abs(refresh.rotation) > 5f
        }
        scenario.onActivity { activity ->
            activity.workspace.selected!!.loading = false
            activity.workspace.changed()
        }
        waitFor(scenario) { activity ->
            val refresh = findControl(activity.window.decorView, "Refresh page") ?: return@waitFor false
            abs(refresh.rotation) < 1f
        }
    }

    @Test fun vaultStoresProfileScopedSnapshotAndBuildsContinuityHandoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = ChatVault(context) { }
        val id = "instrumentation-${UUID.randomUUID()}"
        val transfer = "transfer-${UUID.randomUUID()}"
        val profileA = "profile-a-${UUID.randomUUID()}"
        val profileB = "profile-b-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("text", "opening question $id"))
            .put(JSONObject().put("role", "assistant").put("text", "opening answer"))
            .put(JSONObject().put("role", "user").put("text", "latest unfinished request"))
            .put(JSONObject().put("role", "assistant").put("text", "latest response"))
        val snapshot = JSONObject()
            .put("id", id).put("title", "Instrumentation Vault Chat")
            .put("url", "https://chatgpt.com/c/$id")
            .put("createdAt", now).put("updatedAt", now)
            .put("firstSignature", "user:opening question $id")
            .put("messages", messages)
            .toString()
        vault.begin("tab-test", profileA, JSONObject()
            .put("transfer", transfer).put("chatId", id).put("title", "Instrumentation Vault Chat")
            .put("url", "https://chatgpt.com/c/$id").put("createdAt", now).put("updatedAt", now)
            .put("firstSignature", "user:opening question $id").put("messages", messages.length())
            .put("totalChunks", 1).put("chars", snapshot.length))
        vault.chunk("tab-test", JSONObject().put("transfer", transfer).put("index", 0).put("data", snapshot))
        vault.end("tab-test", JSONObject().put("transfer", transfer))
        val end = SystemClock.elapsedRealtime() + 10_000
        while (vault.summaries().none { it.id == id } && SystemClock.elapsedRealtime() < end) Thread.sleep(40)
        assertTrue("Vault snapshot was not committed locally", vault.summaries().any { it.id == id && it.profileId == profileA && it.messages == 4 })

        vault.stage(id, profileA)
        val stagedEnd = SystemClock.elapsedRealtime() + 5_000
        while (vault.pendingResponse(profileA) == null && SystemClock.elapsedRealtime() < stagedEnd) Thread.sleep(30)
        assertNotNull("Owning profile should receive staged continuity", vault.pendingResponse(profileA))
        assertNull("Another Bubble profile must never receive this handoff", vault.pendingResponse(profileB))

        val latch = CountDownLatch(1)
        var handoff: VaultHandoff? = null
        vault.handoff(id) { handoff = it; latch.countDown() }
        assertTrue("Vault handoff callback timed out", latch.await(5, TimeUnit.SECONDS))
        val text = requireNotNull(handoff).text
        assertTrue(text.contains("[CONTINUITY HANDOFF — PREVIOUS CHAT]"))
        assertTrue(text.contains("opening question $id"))
        assertTrue(text.contains("latest unfinished request"))
        assertTrue(text.contains("[END PREVIOUS CHAT]"))
        assertTrue(requireNotNull(handoff).complete)

        val exportLatch = CountDownLatch(1)
        var transcriptExport: ChatTranscriptExport? = null
        ChatTranscriptExports.beginById(context, vault, id, profileA) {
            transcriptExport = it
            exportLatch.countDown()
        }
        assertTrue("Staged transcript export callback timed out", exportLatch.await(5, TimeUnit.SECONDS))
        val export = requireNotNull(transcriptExport)
        assertEquals(id, export.sourceId)
        assertTrue(export.filename.endsWith("-transcript.md"))
        val markdown = export.file.readText()
        assertTrue(markdown.contains("# ChatGPT conversation transcript"))
        assertTrue(markdown.contains("- Messages: 4"))
        assertTrue(markdown.contains("opening question $id"))
        assertTrue(markdown.contains("latest unfinished request"))
        ChatTranscriptExports.discard(export)

        vault.clearPending(id)
        vault.delete(id)
    }

    private fun findControl(view: View, prefix: String): View? {
        val description = view.contentDescription?.toString().orEmpty()
        if (view.visibility == View.VISIBLE && description.startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findControl(view.getChildAt(i), prefix)?.let { return it }
        return null
    }

    private fun tabTray(activity: BrowserActivity): TabTray? = runCatching {
        BrowserActivity::class.java.getDeclaredField("tray").apply { isAccessible = true }.get(activity) as? TabTray
    }.getOrNull()

    private fun waitFor(
        scenario: ActivityScenario<BrowserActivity>,
        timeoutMs: Long = 20_000,
        predicate: (BrowserActivity) -> Boolean
    ) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var success = false
        while (!success && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { success = predicate(it) }
            if (!success) Thread.sleep(80)
        }
        assertTrue("UI/Vault runtime condition timed out", success)
    }

    /**
     * This test exercises native toolbar gesture, dialog-window glass configuration, and refresh
     * animation state. None of those assertions depends on a network page reaching first paint.
     * Waiting for a synthetic Gecko page here made this first runtime class race cold Gecko startup
     * on SwiftShader. Launch the real BrowserActivity and wait only for the native workspace/control
     * surface that the test actually touches.
     */
    private fun withBrowser(test: (ActivityScenario<BrowserActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
            waitFor(scenario, 45_000) { activity ->
                if (!activity.workspace.ready) return@waitFor false
                val control = findControl(activity.window.decorView, "Workspace tabs") ?: return@waitFor false
                control.isAttachedToWindow && control.isLaidOut && control.width > 0 && control.height > 0
            }
            test(scenario)
        }
    }
}
