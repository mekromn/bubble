package com.mekromn.bubble

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DownloadRecord(val id: String, val name: String, val mime: String, val profile: String,
    var state: String = "Choose location", var uri: String = "", var bytes: Long = 0, var error: String = "")
internal class DownloadTask(val record: DownloadRecord, val response: WebResponse) {
    val cancelled = AtomicBoolean(false)
    @Volatile var output: Closeable? = null
}

/** Consume the ORIGINAL Gecko response. No second request, cookies export, shared cookie jar,
 * JavaScript binary bridge, or credential-bearing URL in a notification/history/log.
 */
internal object BrowserDownloads {
    private val main = Handler(Looper.getMainLooper())
    private val disk = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-download-history").apply { isDaemon = true } }
    private val closers = Executors.newCachedThreadPool { r -> Thread(r, "Bubble-download-close").apply { isDaemon = true } }
    private var initialized = false
    private var loaded = false
    private lateinit var app: Context
    private lateinit var ledger: AtomicFile
    val records = ArrayList<DownloadRecord>()
    internal val tasks = LinkedHashMap<String, DownloadTask>()
    private val listeners = LinkedHashSet<() -> Unit>()
    const val CHANNEL = "bubble-file-downloads-v1"
    const val ITEM_NOTICE = 932
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true; app = context.applicationContext
        ledger = AtomicFile(File(app.filesDir, "downloads-v1.json"))
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "File downloads", NotificationManager.IMPORTANCE_LOW))
        disk.execute {
            val recovered = ArrayList<DownloadRecord>()
            runCatching {
                if (ledger.baseFile.exists()) {
                    require(ledger.baseFile.length() < 1024 * 1024)
                    val array = JSONArray(ledger.openRead().bufferedReader().use { it.readText() })
                    for (i in 0 until minOf(100, array.length())) {
                        val o = array.getJSONObject(i)
                        val r = DownloadRecord(o.getString("id"), o.getString("name"), o.getString("mime"), o.optString("profile"),
                            o.getString("state"), o.optString("uri"), o.optLong("bytes"), o.optString("error"))
                        UUID.fromString(r.id)
                        if (r.state !in setOf("Saved", "Failed", "Cancelled")) {
                            if (r.uri.startsWith("content:")) runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(r.uri)) }
                            r.state = "Failed"; r.error = "Interrupted. Retry the original download link."; r.uri = ""
                        }
                        recovered += r
                    }
                }
            }
            main.post {
                recovered.filter { old -> records.none { it.id == old.id } }.forEach { records += it }
                loaded = true; changed(true)
            }
        }
    }
    fun receive(context: Context, profileId: String, response: WebResponse, present: Boolean) {
        initialize(context)
        if (response.body == null || (response.statusCode != 0 && response.statusCode !in 200..299) || tasks.size >= 12) {
            closers.execute { runCatching { response.body?.close() } }
            Toast.makeText(app, "Download unavailable. Retry the file link after checking the connection.", Toast.LENGTH_LONG).show()
            return
        }
        fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        val record = DownloadRecord(UUID.randomUUID().toString(), FileNames.download(response.uri, header("Content-Disposition")),
            FileNames.mime(header("Content-Type")), profileId)
        val task = DownloadTask(record, response)
        records.add(0, record); tasks[record.id] = task
        response.setReadTimeoutMillis(60_000)
        changed(true); notify(record)
        // The Gecko channel is suspended until its body is first read. Choosing a destination
        // does not start an in-memory whole-file download. Close abandoned responses promptly.
        main.postDelayed({ if (tasks[record.id] === task && record.state == "Choose location") cancel(record.id) }, 10 * 60_000L)
        if (present && !FileUi.busy) choose(app, record.id)
        else Workspace.peek()?.let { it.notice = "A file is ready to save. Open Downloads from the browser menu."; it.changed() }
    }
    fun choose(context: Context, id: String) {
        if (tasks[id]?.record?.state != "Choose location") return
        if (!FileUi.begin(id)) return
        try { context.startActivity(Intent(context, SaveDownloadActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)) }
        catch (_: RuntimeException) { FileUi.end(id); notify(tasks[id]!!.record) }
    }
    fun show(context: Context) {
        initialize(context)
        val token = UUID.randomUUID().toString()
        if (!FileUi.begin(token)) return
        try { context.startActivity(Intent(context, DownloadsActivity::class.java).putExtra("uiToken", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { FileUi.end(token) }
    }
    fun cancel(id: String) {
        val task = tasks[id] ?: return
        task.cancelled.set(true)
        closers.execute { runCatching { task.response.body?.close() }; runCatching { task.output?.close() } }
        if (task.record.state == "Choose location") {
            tasks.remove(id); task.record.state = "Cancelled"; changed(true); notify(task.record)
        }
    }
    internal fun complete(task: DownloadTask, success: Boolean, count: Long, message: String) {
        tasks.remove(task.record.id)
        task.record.bytes = count
        task.record.state = if (success) "Saved" else if (task.cancelled.get()) "Cancelled" else "Failed"
        task.record.error = message
        if (!success) task.record.uri = ""
        changed(true); notify(task.record)
        Toast.makeText(app, if (success) "Saved ${task.record.name}" else "${task.record.state}: ${task.record.name}. $message", Toast.LENGTH_LONG).show()
    }
    internal fun changed(save: Boolean = false) {
        listeners.toList().forEach { it() }
        if (!save || !loaded) return
        val snapshot = records.take(100).map { it.copy() }
        disk.execute {
            var stream: java.io.FileOutputStream? = null
            try {
                val array = JSONArray()
                snapshot.forEach { r -> array.put(JSONObject().put("id", r.id).put("name", r.name).put("mime", r.mime)
                    .put("profile", r.profile).put("state", r.state).put("uri", r.uri).put("bytes", r.bytes).put("error", r.error)) }
                stream = ledger.startWrite(); stream.write(array.toString().toByteArray()); ledger.finishWrite(stream)
            } catch (_: Exception) { stream?.let { ledger.failWrite(it) } }
        }
    }
    fun listen(listener: () -> Unit) { listeners += listener; listener() }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    fun open(context: Context, record: DownloadRecord) {
        if (record.state != "Saved" || !record.uri.startsWith("content:")) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(record.uri), record.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { Toast.makeText(context, "No app could open this file. It is saved in the location you selected.", Toast.LENGTH_LONG).show() }
    }
    internal fun notice(record: DownloadRecord): Notification {
        val pending = PendingIntent.getActivity(app, 0, Intent(app, DownloadsActivity::class.java)
            .setData(Uri.parse("bubble-download:${record.id}")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${record.state} · ${record.name}")
            .setContentText(if (record.error.isNotBlank()) record.error else "${record.bytes / 1024} KiB · tap for file controls")
            .setContentIntent(pending).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (record.state == "Saving") {
            val cancel = PendingIntent.getService(app, 0, Intent(app, FileDownloadService::class.java)
                .setAction("cancel").setData(Uri.parse("bubble-download:${record.id}")).putExtra("id", record.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setOngoing(true).setProgress(0, 0, true).addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        } else builder.setAutoCancel(true)
        return builder.build()
    }
    internal fun notify(record: DownloadRecord) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { app.getSystemService(NotificationManager::class.java).notify(record.id, ITEM_NOTICE, notice(record)) }
    }
}

/** A system Save As picker is the explicit user authorization for the destination. */
class SaveDownloadActivity : Activity() {
    private var id = ""
    private var launched = false
    private var submitted = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id = intent.getStringExtra("id").orEmpty()
        val task = BrowserDownloads.tasks[id]
        if (task == null || task.record.state != "Choose location" || !FileUi.begin(id)) { finish(); return }
        launched = state?.getBoolean("launched") ?: false
        if (!launched) {
            launched = true
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(task.record.mime).putExtra(Intent.EXTRA_TITLE, task.record.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 502) }
            catch (_: RuntimeException) { BrowserDownloads.cancel(id); finish() }
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("launched", launched); super.onSaveInstanceState(state) }
    @Deprecated("Native Save As result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 502) return
        val task = BrowserDownloads.tasks[id]
        val uri = data?.data
        if (result == RESULT_OK && uri?.scheme == "content" && task != null) {
            // Persist only the specific file grant selected by the user, not folder access.
            runCatching { contentResolver.takePersistableUriPermission(uri,
                data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }
            task.record.uri = uri.toString()
            task.record.state = "Saving"; BrowserDownloads.changed(true)
            try {
                startForegroundService(Intent(this, FileDownloadService::class.java).putExtra("id", id).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                submitted = true
            } catch (_: RuntimeException) {
                BrowserDownloads.cancel(id)
                Thread { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }.start()
                BrowserDownloads.complete(task, false, 0, "Android blocked the download service. Retry from the page.")
            }
        } else BrowserDownloads.cancel(id)
        FileUi.end(id); finish()
    }
    override fun onDestroy() {
        if (isFinishing) { if (!submitted) BrowserDownloads.cancel(id); FileUi.end(id) }
        super.onDestroy()
    }
}

/** Visible, finite download work only. All I/O is off the main thread with 64KiB buffers. */
class FileDownloadService : Service() {
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "Bubble-file-download").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val running = LinkedHashSet<String>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id").orEmpty()
        if (intent?.action == "cancel") { BrowserDownloads.cancel(id); if (running.isEmpty()) stopSelf(); return START_NOT_STICKY }
        val task = BrowserDownloads.tasks[id]
        if (task == null || task.record.state != "Saving" || intent?.dataString != task.record.uri) { if (running.isEmpty()) stopSelf(); return START_NOT_STICKY }
        startForeground(930, Notification.Builder(this, BrowserDownloads.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Saving browser files").setContentText("Downloads continue while you use other apps.").setOngoing(true).build())
        if (!running.add(id)) return START_NOT_STICKY
        BrowserDownloads.notify(task.record)
        workers.execute { copy(task) }
        return START_NOT_STICKY
    }
    private fun copy(task: DownloadTask) {
        val uri = Uri.parse(task.record.uri)
        var count = 0L; var success = false; var error = ""
        try {
            if (task.cancelled.get()) throw IOException("Cancelled")
            val source = requireNotNull(task.response.body)
            val destination = contentResolver.openOutputStream(uri, "w") ?: throw IOException("No output")
            task.output = destination
            source.use { input -> destination.use { output ->
                val buffer = ByteArray(64 * 1024); var last = 0L
                while (true) {
                    if (task.cancelled.get()) throw IOException("Cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n); count += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - last >= 750) {
                        last = now; val bytes = count
                        main.post { task.record.bytes = bytes; BrowserDownloads.changed(); BrowserDownloads.notify(task.record) }
                    }
                }
                output.flush()
            } }
            val headers = task.response.headers
            val encoding = headers.entries.firstOrNull { it.key.equals("Content-Encoding", true) }?.value
            val expected = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull()
            if ((encoding.isNullOrBlank() || encoding.equals("identity", true)) && expected != null && expected >= 0 && count != expected) throw IOException("Incomplete body")
            if (task.cancelled.get()) throw IOException("Cancelled")
            success = true
        } catch (_: Exception) {
            val removed = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
            error = if (task.cancelled.get()) "" else "Check connection, free space and the chosen file provider; retry the original link."
            if (!removed) error += " An incomplete file may remain in the selected folder."
        } finally {
            runCatching { task.response.body?.close() }; runCatching { task.output?.close() }; task.output = null
        }
        main.post {
            BrowserDownloads.complete(task, success, count, error)
            running.remove(task.record.id)
            if (running.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { running.toList().forEach(BrowserDownloads::cancel); stopSelf() }
    override fun onDestroy() { running.toList().forEach(BrowserDownloads::cancel); workers.shutdown(); super.onDestroy() }
}

/** Local file history only; no download URLs or account credentials are persisted. */
class DownloadsActivity : Activity() {
    private var token = ""
    private lateinit var column: LinearLayout
    private val changed: () -> Unit = { render() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        BrowserDownloads.initialize(this)
        token = intent.getStringExtra("uiToken") ?: UUID.randomUUID().toString()
        if (!FileUi.begin(token)) { finish(); return }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onStart() { super.onStart(); if (::column.isInitialized) BrowserDownloads.listen(changed) }
    override fun onStop() { BrowserDownloads.unlisten(changed); super.onStop() }
    override fun onDestroy() { FileUi.end(token); super.onDestroy() }
    private fun render() {
        column.removeAllViews()
        column.addView(Ui.text(this, "Downloads", 22f, Ui.TEXT, true))
        for (r in BrowserDownloads.records.take(100)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 18, 12, 18) }
            row.addView(Ui.text(this, r.name, 16f, Ui.TEXT, true))
            row.addView(Ui.text(this, "${r.state} · ${r.bytes} bytes\n${r.error}", 13f, Ui.MUTED))
            val label = when (r.state) { "Choose location" -> "Save file"; "Saving" -> "Cancel"; "Saved" -> "Open file"; else -> "Retry using the original file link" }
            row.addView(Ui.text(this, label, 15f).apply {
                minHeight = Ui.dp(context, 48f); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    when (r.state) {
                        "Choose location" -> { FileUi.end(token); finish(); BrowserDownloads.choose(this@DownloadsActivity, r.id) }
                        "Saving" -> BrowserDownloads.cancel(r.id)
                        "Saved" -> { FileUi.end(token); BrowserDownloads.open(this@DownloadsActivity, r); finish() }
                    }
                }
            })
            column.addView(row)
        }
        if (BrowserDownloads.records.isEmpty()) column.addView(Ui.text(this, "Download a file from a webpage, then choose where to save it.", 15f))
        column.addView(Ui.text(this, "Done", 16f).apply { minHeight = Ui.dp(context, 48f); setOnClickListener { finish() } })
    }
}
