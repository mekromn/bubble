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
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** Gecko file prompts are owned by Bubble: native multi-picker -> one staged archive -> original tab. */
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
            addView(Ui.text(this@FloatingFileActivity, "Opening Bubble Archive Picker…", 16f))
            addView(ProgressBar(this@FloatingFileActivity))
            addView(Ui.text(this@FloatingFileActivity, "Cancel", 15f).apply {
                gravity = Gravity.CENTER; minHeight = Ui.dp(context, 48f)
                setOnClickListener { finishRequest(request, emptyList(), null) }
            })
        }
        setContentView(panel)
        if (!request.pickerStarted) {
            request.pickerStarted = true
            val pick = Intent(this, ArchivePickerActivity::class.java).apply {
                putExtra(ArchivePickerActivity.EXTRA_TAB_ID, request.tabId)
                putExtra(ArchivePickerActivity.EXTRA_REQUEST_ID, request.id)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            try { startActivityForResult(pick, PICK) }
            catch (_: RuntimeException) { finishRequest(request, emptyList(), "Bubble could not open Archive Picker.") }
        }
    }

    @Deprecated("Native archive picker result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK) return
        val request = pending?.takeIf { it.id == token } ?: run { finish(); return }
        if (result != RESULT_OK) { finishRequest(request, emptyList(), null); return }
        val path = data?.getStringExtra(ArchivePickerActivity.RESULT_LOCAL_PATH).orEmpty()
        val file = File(path)
        if (path.isBlank() || !file.isFile || !file.canRead()) {
            finishRequest(request, emptyList(), "Archive Picker did not return a readable attachment.")
            return
        }
        finishRequest(request, listOf(Uri.fromFile(file)), null)
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
            var host = WeakReference<FloatingFileActivity>(null)
            var pickerStarted = false
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
            } catch (_: RuntimeException) { finishRequest(request, emptyList(), "Android blocked the attachment picker. Return to the chat and try again.") }
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
            val accepted = valid && !request.prompt.isComplete && files.isNotEmpty()
            if (!accepted) UploadStaging.discard(request.app, request.tabId, request.id)
            try {
                if (!request.prompt.isComplete) request.result.complete(
                    if (accepted) request.prompt.confirm(request.app, files.toTypedArray()) else request.prompt.dismiss())
            } catch (_: RuntimeException) {
                UploadStaging.discard(request.app, request.tabId, request.id)
                if (!request.prompt.isComplete) request.result.complete(request.prompt.dismiss())
                Toast.makeText(request.app, "The page could not accept this attachment. Please retry.", Toast.LENGTH_LONG).show()
            }
            if (error != null) Toast.makeText(request.app, error, Toast.LENGTH_LONG).show()
            request.host.get()?.finish()
            FileUi.end(request.id)
        }
    }
}
