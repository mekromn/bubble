package com.mekromn.bubble

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings

/** Direct notification Activity PendingIntent: no service/broadcast notification trampoline.
 * This transparent task hands off to the overlay, or the browser when overlays are unavailable. */
class NotificationReturnActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getStringExtra(BrowserActivity.EXTRA_TAB)
        if (!Settings.canDrawOverlays(this) || Workspace.peek()?.visible == true) {
            startActivity(Intent(this, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (id != null) putExtra(BrowserActivity.EXTRA_TAB, id)
            })
            finish(); return
        }
        val reply = object : ResultReceiver(main) {
            override fun onReceiveResult(code: Int, data: Bundle?) { if (code == 0) fallback(id) else finish() }
        }
        try {
            startForegroundService(Intent(this, BubbleService::class.java)
                .putExtra(BubbleService.MODE, intent.getStringExtra(BubbleService.MODE) ?: FloatingMode.BUBBLE.name)
                .putExtra(BrowserActivity.EXTRA_TAB, id).putExtra(BubbleService.READY, reply)
                .putExtra(BubbleService.FORCE_BUBBLE, intent.getBooleanExtra(BubbleService.FORCE_BUBBLE, false)))
            main.postDelayed({ if (!isFinishing) fallback(id) }, 15_000)
        } catch (_: RuntimeException) { fallback(id) }
    }
    private fun fallback(id: String?) {
        if (isFinishing) return
        startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_TAB, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object {
        internal fun pending(context: Context, tabId: String?, mode: FloatingMode, forceBubble: Boolean = false): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, NotificationReturnActivity::class.java).apply {
                data = Uri.parse("bubble://notification/${mode.name}/${tabId ?: "workspace"}/$forceBubble")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(BrowserActivity.EXTRA_TAB, tabId); putExtra(BubbleService.MODE, mode.name)
                putExtra(BubbleService.FORCE_BUBBLE, forceBubble)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
