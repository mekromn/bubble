package com.mekromn.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.Executors
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** A transparent, separate task for user-requested uploads. It never promotes the fullscreen
 * browser merely to show Android's file picker; the original floating chat retains its session. */
class FloatingFileActivity : Activity() {
    private var token = ""
    private var validating = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        token = intent.getStringExtra(TOKEN).orEmpty()
        val request = pending?.takeIf { it.token == token && !it.prompt.isComplete }
        if (request == null) { finish(); return }
        if (state == null) {
            val types = request.prompt.mimeTypes.orEmpty().filter { it.contains('/') }.toTypedArray()
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (types.size == 1) types[0] else "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivityForResult(pick, PICK) }
            catch (_: RuntimeException) { respond(emptyList()) }
        }
    }
    @Deprecated("Native file-result bridge") override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK) return
        val request = pending?.takeIf { it.token == token } ?: run { finish(); return }
        val selected = ArrayList<Uri>()
        if (result == RESULT_OK) {
            data?.data?.let { selected += it }
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected += clip.getItemAt(i).uri }
        }
        val multiple = request.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
        val candidates = selected.distinct().let { if (multiple) it else it.take(1) }
        validating = true
        io.execute {
            val valid = candidates.filter { uri ->
                uri.scheme == "content" && uri.authority?.startsWith(packageName) != true && runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
            }
            runOnUiThread { validating = false; respond(valid) }
        }
    }
    private fun respond(uris: List<Uri>) {
        val request = pending?.takeIf { it.token == token }
        if (request != null) {
            pending = null
            if (!request.prompt.isComplete) {
                val validSession = Workspace.peek()?.tabs?.any { it.id == request.tabId && it.session === request.session } == true
                try {
                    request.result.complete(if (validSession && uris.isNotEmpty()) request.prompt.confirm(applicationContext, uris.toTypedArray()) else request.prompt.dismiss())
                } catch (_: RuntimeException) {
                    if (!request.prompt.isComplete) request.result.complete(request.prompt.dismiss())
                }
            }
        }
        finish()
    }
    override fun onDestroy() {
        if (isFinishing && !validating) {
            pending?.takeIf { it.token == token }?.let {
                pending = null
                if (!it.prompt.isComplete) it.result.complete(it.prompt.dismiss())
            }
        }
        super.onDestroy()
    }
    companion object {
        private const val TOKEN = "bubble.file.request"
        private const val PICK = 501
        private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Bubble-file-validation").apply { isDaemon = true } }
        private data class Request(val token: String, val tabId: String, val session: GeckoSession,
            val prompt: GeckoSession.PromptDelegate.FilePrompt, val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>)
        private var pending: Request? = null
        internal fun launch(context: Context, tabId: String, session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (pending != null) return GeckoResult.fromValue(prompt.dismiss())
            val request = Request(UUID.randomUUID().toString(), tabId, session, prompt, GeckoResult())
            pending = request
            try {
                context.startActivity(Intent(context, FloatingFileActivity::class.java).putExtra(TOKEN, request.token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (_: RuntimeException) { pending = null; request.result.complete(prompt.dismiss()) }
            return request.result
        }
    }
}
