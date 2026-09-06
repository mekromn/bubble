package com.mekromn.bubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DownloadRecord(val id: String, val name: String, val mime: String, val profile: String,
    var state: String = "Choose location", var uri: String = "", var bytes: Long = 0, var error: String = "")
internal class DownloadTask(val record: DownloadRecord, val response: WebResponse) {
    val cancelled = AtomicBoolean(false)
    @Volatile var output: Closeable? = null
}

/** Consume the ORIGINAL Gecko response. No refetch, exported cookies, shared cookie jar,
 * JavaScript binary bridge, or credential-bearing URL in notifications/history/logs. */
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
