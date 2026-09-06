package com.mekromn.bubble

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.Executors

/** Visible finite transfer, off-main streaming, cancellation and incomplete-file cleanup. */
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
