package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.*
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance is bytes transferred, not merely seeing a chooser or a download notification. */
@RunWith(AndroidJUnit4::class)
class FileTransferRuntimeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val ui = ins.uiAutomation
    private val text = "Bubble attachment UTF-8 ✓\n".toByteArray()
    private val binary = ByteArray(1024) { (it * 17).toByte() }
    private data class Upload(val cookies: String, val body: ByteArray)
    private class Server : Closeable {
        val socket = ServerSocket(0)
        val pool = Executors.newCachedThreadPool()
        val uploads = ConcurrentLinkedQueue<Upload>()
        val downloads = AtomicInteger()
        val port get() = socket.localPort
        init { pool.execute {
            while (!socket.isClosed) try { val client = socket.accept(); pool.execute { try { client.use { connection ->
                connection.soTimeout = 15_000
                val input = BufferedInputStream(connection.getInputStream())
                fun line(): String {
                    val out = ByteArrayOutputStream()
                    while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) out.write(b) }
                    return out.toString("ISO-8859-1")
                }
                val request = line(); if (request.isEmpty()) return@use
                val headers = LinkedHashMap<String, String>()
                while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                val count = headers["content-length"]?.toIntOrNull() ?: 0
                require(count in 0..(64 * 1024 * 1024))
                val payload = ByteArray(count); var offset = 0
                while (offset < count) { val n = input.read(payload, offset, count - offset); if (n < 0) throw EOFException("Short request"); offset += n }
                val cookie = headers["cookie"].orEmpty()
                val account = if (cookie.contains("identity=WORK")) "WORK" else "DEFAULT"
                var status = "200 OK"; var extra = ""; var mime = "text/html; charset=UTF-8"
                val bytes: ByteArray
                when {
                    request.contains("/upload") -> {
                        uploads.add(Upload(cookie, payload)); mime = "text/plain"; bytes = "accepted".toByteArray()
                    }
                    request.contains("/redirect") -> {
                        status = "302 Found"; extra = "Location: /download\r\n"; bytes = byteArrayOf()
                    }
                    request.contains("/download") -> {
                        downloads.incrementAndGet(); mime = "application/octet-stream"
                        extra = "Content-Disposition: attachment; filename*=UTF-8''report%20$account.bin\r\n"
                        bytes = ("ACCOUNT:$account\n").toByteArray() + ByteArray(8192) { (it * 31).toByte() }
                    }
                    else -> {
                        val selected = if (request.contains("account=WORK")) "WORK" else "DEFAULT"
                        extra = "Set-Cookie: identity=$selected; Path=/; HttpOnly; SameSite=Lax\r\n"
                        bytes = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>FILES-READY</title><style>body{margin:0;background:#151515;color:white}button{display:block;margin:8px;width:220px;height:48px;font-size:18px}</style><button onclick="document.querySelector('input').click()">Attach files</button><button onclick="location.href='/redirect'">Download protected file</button><button onclick="const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(['Generated exact bytes ✓'],{type:'text/plain'}));a.download='generated.txt';a.click()">Download generated file</button><input type="file" multiple style="display:none"><script>document.querySelector('input').onchange=async(e)=>{try{const f=e.target.files;const body=new FormData();for(const file of f)body.append('files',file,file.name);const r=await fetch('/upload',{method:'POST',body});document.title=r.ok?'UPLOADED|'+f.length+'|'+Array.from(f).map(x=>x.name).join(','):'UPLOAD-FAILED';}catch(e){document.title='UPLOAD-ERROR-'+e.name}}</script>""".toByteArray()
                    }
                }
                connection.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n$extra" + "Connection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
                connection.getOutputStream().write(bytes)
            } } catch (_: IOException) {
                // Navigation/cancellation can close a real browser socket. Catch inside the
                // worker; the accept-loop catch cannot intercept a worker's Broken pipe.
                // Transfer assertions below still require complete original byte payloads.
            } } } catch (_: IOException) { if (socket.isClosed) break }
        } }
        override fun close() { socket.close(); pool.shutdownNow() }
    }
    @Test fun realDocumentPickerUploadsExactMultipleFilesAndCancellationCanRetry() = fixture { scenario, server ->
        pageTap(0)
        await("Real Android picker opens") { node { it.packageName?.toString()?.endsWith("documentsui") == true } != null }
        shell("input keyevent KEYCODE_BACK")
        await("Cancellation returns without sending data") { main { !FileUi.busy } }
        assertTrue(server.uploads.isEmpty())
        pageTap(0); providerRoot()
        tapNode({ it.text?.toString() == "attach-one.txt" }, hold = true)
        tapNode({ it.text?.toString() == "attach-two.bin" })
        tapNode({ it.text?.toString()?.equals("Open", true) == true })
        await("Selected bytes arrive at server") { main { Workspace.peek()?.selected?.title?.startsWith("UPLOADED|2|") == true } }
        val received = server.uploads.last()
        assertTrue(received.cookies.contains("identity=DEFAULT"))
        assertTrue("Text bytes changed", contains(received.body, text))
        assertTrue("Binary bytes changed", contains(received.body, binary))
        assertTrue(received.body.toString(Charsets.ISO_8859_1).contains("filename=\"attach-one.txt\""))
        assertTrue(received.body.toString(Charsets.ISO_8859_1).contains("filename=\"attach-two.bin\""))
        screenshot("files-upload-fullscreen.png")
        scenario.onActivity { assertTrue(it.selectedSession!!.isOpen) }
        File(folder(), "upload-fullscreen.txt").writeText("Real SAF multi-selection, original names and exact text/binary multipart bytes verified. Cancellation sent no bytes and retry succeeded.\n")
    }
    @Test fun floatingUploadUsesOriginalTabAndItsAccountProfile() = fixture { scenario, server ->
        var original: org.mozilla.geckoview.GeckoSession? = null
        scenario.onActivity {
            val profile = it.workspace.createProfile("Files ${UUID.randomUUID()}")
            it.workspace.create("http://127.0.0.1:${server.port}/page?account=WORK", profile.id)
            original = it.selectedSession
        }
        await("Work profile page loaded") { main { Workspace.peek()?.selected?.title == "FILES-READY" && Workspace.peek()?.selected?.painted == true } }
        tapNode({ it.contentDescription?.toString() == "Open interactive floating chat" })
        await("Floating view attached") { main { BubbleService.active?.window?.geckoView?.session === original && BubbleService.active?.window?.isTransitioning == false } }
        pageTap(0); providerRoot(); tapNode({ it.text?.toString() == "attach-one.txt" })
        await("Floating upload completed") { main { Workspace.peek()?.selected?.title?.startsWith("UPLOADED|1|") == true && !FileUi.busy } }
        val received = server.uploads.last()
        assertTrue(received.cookies.contains("identity=WORK")); assertTrue(contains(received.body, text))
        assertTrue(main { BubbleService.active?.window?.geckoView?.session === original && BubbleService.active?.window?.geckoView?.isShown == true })
        assertNotEquals("File picker promoted fullscreen", Lifecycle.State.RESUMED, scenario.state)
        screenshot("files-upload-floating.png")
        File(folder(), "upload-floating.txt").writeText("Real provider selection uploaded with WORK profile cookie, original GeckoSession retained, floating window visible again without promoting fullscreen.\n")
    }
    @Test fun authenticatedRedirectAndGeneratedBlobDownloadsSaveExactBytes() = fixture { scenario, server ->
        scenario.onActivity {
            val profile = it.workspace.createProfile("Downloads ${UUID.randomUUID()}")
            it.workspace.create("http://127.0.0.1:${server.port}/page?account=WORK", profile.id)
        }
        await("Download source ready") { main { Workspace.peek()?.selected?.painted == true && Workspace.peek()?.selected?.title == "FILES-READY" } }
        pageTap(1)
        await("Authenticated response reached native downloader") { main { BrowserDownloads.records.firstOrNull()?.name == "report WORK.bin" && FileUi.busy } }
        providerRoot(); savePicker()
        await("HTTP download fully saved") { main { BrowserDownloads.records.firstOrNull()?.state == "Saved" && !FileUi.busy } }
        var record: DownloadRecord? = null
        ins.runOnMainSync { record = BrowserDownloads.records.first().copy() }
        val expected = "ACCOUNT:WORK\n".toByteArray() + ByteArray(8192) { (it * 31).toByte() }
        assertArrayEquals(expected, context.contentResolver.openInputStream(Uri.parse(record!!.uri))!!.use { it.readBytes() })
        assertEquals("Downloader re-fetched a signed response", 1, server.downloads.get())
        pageTap(2)
        await("Generated blob reached downloader") { main { BrowserDownloads.records.firstOrNull()?.name == "generated.txt" && FileUi.busy } }
        providerRoot(); savePicker()
        await("Generated download fully saved") { main { BrowserDownloads.records.firstOrNull()?.name == "generated.txt" && BrowserDownloads.records.firstOrNull()?.state == "Saved" && !FileUi.busy } }
        ins.runOnMainSync { record = BrowserDownloads.records.first().copy() }
        assertArrayEquals("Generated exact bytes ✓".toByteArray(), context.contentResolver.openInputStream(Uri.parse(record!!.uri))!!.use { it.readBytes() })
        pageTap(2)
        await("Cancel Save As is available") { main { FileUi.busy && BrowserDownloads.records.firstOrNull()?.state == "Choose location" } }
        shell("input keyevent KEYCODE_BACK")
        await("Cancelled download closes its source") { main { !FileUi.busy && BrowserDownloads.records.firstOrNull()?.state == "Cancelled" } }
        assertEquals(1, server.downloads.get())
        File(folder(), "download-byte-verification.txt").writeText("Saved original authenticated redirect response exactly once with WORK account. Exact HTTP binary payload and generated UTF-8 Blob bytes verified through granted SAF URIs. Save As cancellation reported Cancelled, not Saved.\n")
        screenshot("files-download-complete.png")
    }
    private fun providerRoot() {
        await("DocumentsUI visible") { node { it.packageName?.toString()?.endsWith("documentsui") == true } != null }
        val rootVisible = node { it.text?.toString() == "Bubble file tests" }
        if (rootVisible == null) tapNode({ it.contentDescription?.toString() in listOf("Show roots", "Navigate up") })
        tapNode({ it.text?.toString() == "Bubble file tests" })
        Thread.sleep(400)
    }
    private fun savePicker() { tapNode({ it.text?.toString()?.equals("Save", true) == true && it.isEnabled }) }
    private fun pageTap(index: Int) {
        var x = 0f; var y = 0f
        ins.runOnMainSync {
            val view = BubbleService.active?.window?.geckoView ?: Workspace.peek()!!.host.get()!!.geckoView
            val p = IntArray(2); view.getLocationOnScreen(p)
            val d = view.resources.displayMetrics.density
            x = p[0] + 90 * d; y = p[1] + (32 + 56 * index) * d
        }
        tap(x, y, false)
    }
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean = (0..(haystack.size - needle.size).coerceAtLeast(-1)).any { i -> needle.indices.all { j -> haystack[i + j] == needle[j] } }
    private fun main(test: () -> Boolean): Boolean { var result = false; ins.runOnMainSync { result = test() }; return result }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        screenshot("files-failure.png")
        val out = StringBuilder(message).append('\n')
        ins.runOnMainSync {
            out.append("FileUi=").append(FileUi.busy).append(" title=").append(Workspace.peek()?.selected?.title).append('\n')
            BrowserDownloads.records.forEach { out.append(it).append('\n') }
        }
        fun describe(n: AccessibilityNodeInfo?, depth: Int = 0) {
            if (n == null || depth > 30) return
            out.append(" ".repeat(depth)).append(n.packageName).append(" text=").append(n.text).append(" desc=").append(n.contentDescription).append('\n')
            for (i in 0 until n.childCount) describe(n.getChild(i), depth + 1)
        }
        ui.windows.forEach { describe(it.root) }; File(folder(), "files-failure.txt").writeText(out.toString()); fail(message)
    }
    private fun node(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && test(n)) return n
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        for (w in ui.windows.sortedByDescending { it.layer }) visit(w.root)?.let { return it }
        return null
    }
    private fun tapNode(test: (AccessibilityNodeInfo) -> Boolean, hold: Boolean = false) {
        var n: AccessibilityNodeInfo? = null
        await("Required real file UI control available") { n = node(test); n != null }
        val rect = Rect(); n!!.getBoundsInScreen(rect); tap(rect.exactCenterX(), rect.exactCenterY(), hold)
    }
    private fun tap(x: Float, y: Float, hold: Boolean) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        try { assertTrue(ui.injectInputEvent(down, true)) } finally { down.recycle() }
        Thread.sleep(if (hold) 1500 else 80)
        val up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(ui.injectInputEvent(up, true)) } finally { up.recycle() }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun folder() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private fun screenshot(name: String) { ui.takeScreenshot()?.let { image -> File(folder(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    private fun fixture(test: (ActivityScenario<BrowserActivity>, Server) -> Unit) {
        val flags = ui.serviceInfo.flags
        ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        Server().use { server ->
            try {
                ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                    await("Workspace ready") { main { Workspace.peek()?.ready == true } }
                    scenario.onActivity { a ->
                        val id = a.workspace.create("http://127.0.0.1:${server.port}/page", ProfilePolicy.DEFAULT_ID).id
                        a.workspace.tabs.map { it.id }.filter { it != id }.forEach(a.workspace::close)
                    }
                    await("File fixture ready") { main { Workspace.peek()?.selected?.painted == true && Workspace.peek()?.selected?.title == "FILES-READY" } }
                    test(scenario, server)
                }
            } finally {
                ins.runOnMainSync {
                    Workspace.peek()?.tabs?.forEach { it.session?.let(FloatingFileActivity::cancelForSession) }
                    BrowserDownloads.tasks.keys.toList().forEach { BrowserDownloads.cancel(it); FileUi.end(it) }
                    for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED, Stage.STOPPED)) {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().filter { it is SaveDownloadActivity || it is FloatingFileActivity || it is DownloadsActivity }.forEach(Activity::finish)
                    }
                }
                context.stopService(Intent(context, BubbleService::class.java))
                ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags }
                shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            }
        }
    }
}
