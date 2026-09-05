package com.mekromn.bubble

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession

/** Runs only on the disposable CI emulator; never grants permissions on a user's device. */
@RunWith(AndroidJUnit4::class)
class FloatingWorkspaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    @Test fun oneAttachedBubbleCanRestoreTheSameWorkspace() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                var ready = false
                repeat(600) {
                    if (!ready) {
                        scenario.onActivity { ready = it.workspace.ready && it.selectedSession?.isOpen == true }
                        if (!ready) Thread.sleep(100)
                    }
                }
                assertTrue("Workspace did not initialize", ready)
                var original: GeckoSession? = null
                scenario.onActivity { original = it.selectedSession }
                // Two start requests must reuse one real overlay, not create duplicate heads.
                repeat(2) {
                    val ack = CountDownLatch(1)
                    var attached = false
                    val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(code: Int, data: Bundle?) {
                            attached = code == 1; ack.countDown()
                        }
                    }
                    scenario.onActivity { activity ->
                        activity.startForegroundService(Intent(activity, BubbleService::class.java).putExtra(BubbleService.READY, reply))
                    }
                    assertTrue("No overlay attachment acknowledgement", ack.await(10, TimeUnit.SECONDS))
                    assertTrue("Overlay creation failed", attached)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(1200)
                val nodes = bubbleNodes()
                assertEquals("Expected exactly one accessible workspace bubble", 1, nodes.size)
                screenshot("floating-workspace.png")
                assertTrue(nodes.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                var resumed = false
                repeat(100) {
                    if (!resumed) {
                        resumed = scenario.state == Lifecycle.State.RESUMED
                        if (!resumed) Thread.sleep(100)
                    }
                }
                assertTrue("Bubble did not restore the browser", resumed)
                scenario.onActivity { assertSame(original, it.selectedSession); assertFalse(it.isFinishing) }
                Thread.sleep(500)
                assertTrue("Restored workspace left a duplicate overlay", bubbleNodes().isEmpty())
            }
        } finally {
            context.stopService(Intent(context, BubbleService::class.java))
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
    private fun bubbleNodes(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val description = node.contentDescription?.toString().orEmpty()
            if (description == "Open Bubble workspace" || description.startsWith("Open workspace,")) result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        automation.windows.forEach { visit(it.root) }
        return result
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
    private fun screenshot(name: String) {
        val image = automation.takeScreenshot() ?: return
        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
