package com.mekromn.bubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private data class TestFiles(val one: String, val two: String, val uris: List<Uri>)
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
            } } catch (_: IOException) { } } } catch (_: IOException) { if (socket.isClosed) break }
        } }
        override fun close() { socket.close(); pool.shutdownNow() }
    }
    @Test fun realDocumentPickerUploadsExactMultipleFilesAndCancellationCanRetry() = fixture { scenario, server, docs ->
        pageTap(0)
        await("Real Android picker opens") { node { it.packageName?.toString()?.endsWith("documentsui") == true } != null }
        shell("input keyevent KEYCODE_BACK")
        await("Cancellation returns without sending data") { main { !FileUi.busy } }
        assertTrue(server.uploads.isEmpty())
        pageTap(0); downloadsRoot()
        tapNode({ it.text?.toString() == docs.one }, hold = true)
        tapNode({ it.text?.toString() == docs.two })
        tapNode({ it.text?.toString()?.equals("Open", true) == true })
        await("Selected bytes arrive at server") { main { Workspace.peek()?.selected?.title?.startsWith("UPLOADED|2|") == true } }
        val received = server.uploads.last()
        assertTrue(received.cookies.contains("identity=DEFAULT"))
        assertTrue("Text bytes changed", contains(received.body, text))
        assertTrue("Binary bytes changed", contains(received.body, binary))
        assertTrue(received.body.toString(Charsets.ISO_8859_1).contains("filename=\"${docs.one}\""))
        assertTrue(received.body.toString(Charsets.ISO_8859_1).contains("filename=\"${docs.two}\""))
        screenshot("files-upload-fullscreen.png")
        scenario.onActivity { assertTrue(it.selectedSession!!.isOpen) }
        File(folder(), "upload-fullscreen.txt").writeText("Real Downloads/SAF multi-selection, original names and exact text/binary multipart bytes verified. Cancellation sent no bytes and retry succeeded.\n")
    }
    @Test fun floatingUploadUsesOriginalTabAndItsAccountProfile() = fixture { scenario, server, docs ->
        var original: org.mozilla.geckoview.GeckoSession? = null
        scenario.onActivity {
            val profile = it.workspace.createProfile("Files ${UUID.randomUUID()}")
            it.workspace.create("http://127.0.0.1:${server.port}/page?account=WORK", profile.id)
            original = it.selectedSession
        }
        await("Work profile page loaded") { main { Workspace.peek()?.selected?.title == "FILES-READY" && Workspace.peek()?.selected?.painted == true } }
        tapActivity(scenario, "Open interactive floating chat")
        await("Floating view attached") { main { BubbleService.active?.window?.geckoView?.session === original && BubbleService.active?.window?.isTransitioning == false } }
        pageTap(0); downloadsRoot(); tapNode({ it.text?.toString() == docs.one })
        await("Floating upload completed") { main { Workspace.peek()?.selected?.title?.startsWith("UPLOADED|1|") == true && !FileUi.busy } }
        val received = server.uploads.last()
        assertTrue(received.cookies.contains("identity=WORK")); assertTrue(contains(received.body, text))
        assertTrue(main { BubbleService.active?.window?.geckoView?.session === original && BubbleService.active?.window?.geckoView?.isShown == true })
        assertNotEquals("File picker promoted fullscreen", Lifecycle.State.RESUMED, scenario.state)
        screenshot("files-upload-floating.png")
        File(folder(), "upload-floating.txt").writeText("Real Downloads/SAF selection uploaded with WORK profile cookie, original GeckoSession retained, floating window visible again without promoting fullscreen.\n")
    }
    @Test fun authenticatedRedirectAndGeneratedBlobDownloadsSaveExactBytes() = fixture { scenario, server, _ ->
        scenario.onActivity {
            val profile = it.workspace.createProfile("Downloads ${UUID.randomUUID()}")
            it.workspace.create("http://127.0.0.1:${server.port}/page?account=WORK", profile.id)
        }
        await("Download source ready") { main { Workspace.peek()?.selected?.painted == true && Workspace.peek()?.selected?.title == "FILES-READY" } }
        pageTap(1)
        await("Authenticated response reached native downloader") { main { BrowserDownloads.records.firstOrNull()?.name == "report WORK.bin" && FileUi.busy } }
        downloadsRoot(); savePicker()
        await("HTTP download fully saved") { main { BrowserDownloads.records.firstOrNull()?.state == "Saved" && !FileUi.busy } }
        var record: DownloadRecord? = null
        ins.runOnMainSync { record = BrowserDownloads.records.first().copy() }
        val expected = "ACCOUNT:WORK\n".toByteArray() + ByteArray(8192) { (it * 31).toByte() }
        assertArrayEquals(expected, context.contentResolver.openInputStream(Uri.parse(record!!.uri))!!.use { it.readBytes() })
        assertEquals("Downloader re-fetched a signed response", 1, server.downloads.get())
        pageTap(2)
        await("Generated blob reached downloader") { main { BrowserDownloads.records.firstOrNull()?.name == "generated.txt" && FileUi.busy } }
        downloadsRoot(); savePicker()
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
    private fun downloadsRoot() {
        await("DocumentsUI visible") { node { it.packageName?.toString()?.endsWith("documentsui") == true } != null }
        node { it.contentDescription?.toString() == "Show roots" }?.let { button ->
            val rect = Rect(); button.getBoundsInScreen(rect); tap(rect.exactCenterX(), rect.exactCenterY(), false)
        }
        await("Downloads root available") { node { it.text?.toString() == "Downloads" && it.isClickable } != null }
        tapNode({ it.text?.toString() == "Downloads" && it.isClickable })
        Thread.sleep(500)
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
        val failureId = System.currentTimeMillis().toString()
        screenshot("files-failure-$failureId.png")
        val out = StringBuilder(message).append('\n')
        ins.runOnMainSync {
            out.append("nativeVisible=").append(Workspace.peek()?.visible).append(" floating=").append(Workspace.peek()?.floatingVisible).append(" selected=").append(Workspace.peek()?.selectedId).append(' ')
            out.append("FileUi=").append(FileUi.busy).append(" title=").append(Workspace.peek()?.selected?.title).append(" url=").append(Workspace.peek()?.selected?.url).append(" error=").append(Workspace.peek()?.selected?.error).append('\n')
            out.append("downloadsAtServer=").append(BrowserDownloads.records.size).append('\n')
            BrowserDownloads.records.forEach { out.append(it).append('\n') }
        }
        fun describe(n: AccessibilityNodeInfo?, depth: Int = 0) {
            if (n == null || depth > 40) return
            out.append(" ".repeat(depth)).append(n.packageName).append(" text=").append(n.text).append(" desc=").append(n.contentDescription).append('\n')
            for (i in 0 until n.childCount) describe(n.getChild(i), depth + 1)
        }
        ui.windows.forEach { describe(it.root) }; File(folder(), "files-failure-$failureId.txt").writeText(out.toString()); fail(message)
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
    private fun tapActivity(scenario: ActivityScenario<BrowserActivity>, label: String, hold: Boolean = false) {
        var rect = Rect()
        scenario.onActivity { activity ->
            fun find(view: View): View? {
                if (view.contentDescription?.toString() == label && view.visibility == View.VISIBLE) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val control = find(activity.window.decorView) ?: fail("Activity control missing: $label")
            assertTrue("Activity control not globally visible: $label", control.getGlobalVisibleRect(rect))
        }
        tap(rect.exactCenterX(), rect.exactCenterY(), hold)
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
    private fun testFiles(): TestFiles {
        val suffix = UUID.randomUUID().toString().take(8)
        val names = listOf("bubble-$suffix-one.txt", "bubble-$suffix-two.bin")
        val data = listOf(text, binary)
        val types = listOf("text/plain", "application/octet-stream")
        val uris = names.indices.map { i ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, names[i])
                put(MediaStore.MediaColumns.MIME_TYPE, types[i])
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(data[i]) }
            uri
        }
        return TestFiles(names[0], names[1], uris)
    }
    private fun fixture(test: (ActivityScenario<BrowserActivity>, Server, TestFiles) -> Unit) {
        val flags = ui.serviceInfo.flags
        ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val docs = testFiles()
        Server().use { server ->
            try {
                ActivityScenario.launch<BrowserActivity>(Intent(context, BrowserActivity::class.java)).use { scenario ->
                    await("Workspace ready") { main { Workspace.peek()?.ready == true } }
                    scenario.onActivity { a ->
                        val id = a.workspace.create("http://127.0.0.1:${server.port}/page", ProfilePolicy.DEFAULT_ID).id
                        a.workspace.tabs.map { it.id }.filter { it != id }.forEach(a.workspace::close)
                    }
                    await("File fixture ready") { main { Workspace.peek()?.selected?.painted == true && Workspace.peek()?.selected?.title == "FILES-READY" } }
                    test(scenario, server, docs)
                }
            } finally {
                ins.runOnMainSync {
                    Workspace.peek()?.tabs?.forEach { it.session?.let(FloatingFileActivity::cancelForSession) }
                    BrowserDownloads.tasks.keys.toList().forEach { BrowserDownloads.cancel(it); FileUi.end(it) }
                    for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED, Stage.STOPPED)) {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().filter { it is SaveDownloadActivity || it is FloatingFileActivity || it is DownloadsActivity }.forEach(Activity::finish)
                    }
                }
                docs.uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                context.stopService(Intent(context, BubbleService::class.java))
                ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags }
                shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            }
        }
    }
}
