package com.mekromn.bubble

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Remembers the explicit app Activity selected from Bubble's Android share chooser.
 *
 * Android does not expose a supported API for asking the system Sharesheet "what was the globally
 * last share target?". Bubble therefore remembers the target the user actually selected from a
 * Bubble-created chooser. A long press can reuse that explicit component; if it disappears or can
 * no longer handle the payload, the normal chooser is used instead.
 */
internal object ShareTargets {
    private const val PREFS = "bubble-share-target-v1"
    private const val LAST_COMPONENT = "last-component"
    internal const val ACTION_CHOSEN = "com.mekromn.bubble.SHARE_TARGET_CHOSEN"
    private const val CALLBACK_REQUEST = 9047

    fun chooser(context: Context, target: Intent, title: String): Intent {
        val callback = Intent(context, ShareTargetReceiver::class.java).setAction(ACTION_CHOSEN)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val sender = PendingIntent.getBroadcast(context, CALLBACK_REQUEST, callback, flags).intentSender
        return Intent.createChooser(target, title, sender)
    }

    fun shareLast(context: Context, target: Intent): Boolean {
        val component = last(context) ?: return false
        val direct = Intent(target).setComponent(component)
        if (context !is Activity) direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(direct, 0) == null) {
            clear(context)
            return false
        }
        return try {
            context.startActivity(direct)
            true
        } catch (_: RuntimeException) {
            clear(context)
            false
        }
    }

    internal fun remember(context: Context, component: ComponentName?) {
        if (component == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_COMPONENT, component.flattenToString()).apply()
    }

    private fun last(context: Context): ComponentName? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_COMPONENT, null) ?: return null
        return ComponentName.unflattenFromString(raw)
    }

    private fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LAST_COMPONENT).apply()
    }
}

/** Explicit, non-exported chooser callback populated by Android with EXTRA_CHOSEN_COMPONENT. */
class ShareTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShareTargets.ACTION_CHOSEN) return
        @Suppress("DEPRECATION")
        val component = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        ShareTargets.remember(context, component)
    }
}
