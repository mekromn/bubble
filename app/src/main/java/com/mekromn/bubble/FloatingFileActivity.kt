package com.mekromn.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.UUID
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** Real Android document picker -> bounded IO staging -> original Gecko file prompt.
 * A request survives Activity recreation; IO never owns an Activity or copies an account cookie.
 */
class FloatingFileActivity : Activity() {
    private var token = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        token = intent.getStringExtra(TOKEN).orEmpty()
        val request = pending?.takeIf { it.id == token && !it.prompt.isComplete }
        if (request == null) { FileUi.end(token); finish(); return }
        request.host = WeakReference(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
            background = Ui.shape(this@FloatingFileActivity, Ui.SURFACE, 20f)
            addView(Ui.text(this@FloatingFileActivity, "Preparing selected attachments…", 16f))
            addView(ProgressBar(this@FloatingFileActivity))
            addView(Ui.text(this@FloatingFileActivity, "Cancel", 15f).apply {
                gravity = Gravity.CENTER; minHeight = Ui.dp(context, 48f)
                setOnClickListener { finishRequest(request, emptyList(), null) }
            })
        }
        setContentView(panel)
        if (!request.pickerStarted) {
            request.pickerStarted = true
            // Product policy: Bubble's picker must not hide arbitrary files merely because the
            // webpage supplied an <input accept=...> / Gecko mimeTypes hint. The user may choose
            // ANY openable document (APK/ZIP/text/archive/source/etc.); the site remains free to
            // accept or reject that file after selection. This affects chooser visibility only and
            // does not bypass any server-side ChatGPT/file-format validation.
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = UploadPickerPolicy.PICKER_MIME
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivityForResult(pick, PICK) }
            catch (_: RuntimeException) { finishRequest(request, emptyList(), "Android could not open the file picker.") }
        }
    }
    @Deprecated("Native document picker result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK) return
        val request = pending?.takeIf { it.id == token } ?: run { finish(); return }
        if (request.copying) return
        if (result != RESULT_OK) { finishRequest(request, emptyList(), null); return }
        val selected = ArrayList<Uri>()
        data?.data?.let { selected += it }
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected += clip.getItemAt(i).uri }
        val chosen = selected.distinct().let { if (request.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) it else it.take(1) }
        if (chosen.isEmpty()) { finishRequest(request, emptyList(), "No attachment was selected."); return }
        request.copying = true
        UploadStaging.io.execute {
            var failure: String? = null
            val staged = try { UploadStaging.prepare(request.app, request.tabId, request.id, chosen, request.job) }
                catch (_: Exception) { failure = "Could not read the selected file. Check free space and the file provider, then try again."; emptyList() }
            main.post { finishRequest(request, staged, failure) }
        }
    }
    override fun onDestroy() {
        val request = pending?.takeIf { it.id == token && it.host.get() === this }
        if (request != null) {
            request.host.clear()
            if (isFinishing) finishRequest(request, emptyList(), null)
        }
        super.onDestroy()
    }
    companion object {
        private const val TOKEN = "bubble.file.request"
        private const val PICK = 501
        private val main = Handler(Looper.getMainLooper())
        private class Request(val app: Context, val tabId: String, val session: GeckoSession,
            val prompt: GeckoSession.PromptDelegate.FilePrompt) {
            val id = UUID.randomUUID().toString()
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val job = UploadStaging.Job()
            var host = WeakReference<FloatingFileActivity>(null)
            var pickerStarted = false
            var copying = false
        }
        private var pending: Request? = null
        internal fun launch(context: Context, tabId: String, session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            UploadStaging.initialize(context.applicationContext)
            if (pending != null || FileUi.busy) return GeckoResult.fromValue(prompt.dismiss())
            val request = Request(context.applicationContext, tabId, session, prompt)
            if (!FileUi.begin(request.id)) return GeckoResult.fromValue(prompt.dismiss())
            pending = request
            try {
                context.startActivity(Intent(context, FloatingFileActivity::class.java).putExtra(TOKEN, request.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (_: RuntimeException) { finishRequest(request, emptyList(), "Android blocked the file picker. Return to the chat and try again.") }
            return request.result
        }
        internal fun cancelForSession(session: GeckoSession) {
            pending?.takeIf { it.session === session }?.let { finishRequest(it, emptyList(), null) }
        }
        private fun finishRequest(request: Request, files: List<Uri>, error: String?) {
            if (pending !== request) {
                UploadStaging.discard(request.app, request.tabId, request.id)
                return
            }
            pending = null
            val valid = Workspace.peek()?.tabs?.any { it.id == request.tabId && it.session === request.session } == true
            val accepted = valid && !request.prompt.isComplete && files.isNotEmpty() && !request.job.cancelled.get()
            if (!accepted) { request.job.cancel(); UploadStaging.discard(request.app, request.tabId, request.id) }
            try {
                if (!request.prompt.isComplete) request.result.complete(
                    if (accepted) request.prompt.confirm(request.app, files.toTypedArray()) else request.prompt.dismiss())
            } catch (_: RuntimeException) {
                UploadStaging.discard(request.app, request.tabId, request.id)
                if (!request.prompt.isComplete) request.result.complete(request.prompt.dismiss())
                Toast.makeText(request.app, "The page could not accept these attachments. Please retry.", Toast.LENGTH_LONG).show()
            }
            if (error != null) Toast.makeText(request.app, error, Toast.LENGTH_LONG).show()
            request.host.get()?.finish()
            FileUi.end(request.id)
        }
    }
}
