package com.mekromn.bubble

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract

/** System Save As selects the specific authorized output file. */
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
            // Persist each granted mode explicitly. Never pass unrelated Intent flags to the
            // URI-permission API, and do not demand a mode the provider did not grant.
            if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            if ((data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
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
