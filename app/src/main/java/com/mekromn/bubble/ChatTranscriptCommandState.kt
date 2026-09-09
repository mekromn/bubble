package com.mekromn.bubble

import android.content.Context

/** Prevents a completed transcript-request turn from re-uploading after a page refresh. */
internal object ChatTranscriptCommandState {
    private const val PREFS = "bubble-chat-transcript-command-v1"

    fun completed(context: Context, tabId: String): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(tabId, "").orEmpty()

    fun markCompleted(context: Context, tabId: String, fingerprint: String) {
        if (fingerprint.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(tabId, fingerprint.take(512)).apply()
    }

    fun clear(context: Context, tabId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(tabId).apply()
    }
}
