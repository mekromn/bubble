package com.mekromn.bubble.browser.navigation

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

class SystemExternalNavigator(
    private val context: Context,
) {
    fun handle(decision: ExternalNavigationDecision, loadFallback: (String) -> Unit): Boolean {
        return when (decision) {
            ExternalNavigationDecision.WebView -> false
            ExternalNavigationDecision.Block -> true
            is ExternalNavigationDecision.ExternalApp -> {
                launchViewIntent(decision.uri)
                true
            }
            is ExternalNavigationDecision.IntentScheme -> {
                launchIntentScheme(decision.uri, loadFallback)
                true
            }
        }
    }

    private fun launchViewIntent(rawUri: String) {
        val intent = Intent(Intent.ACTION_VIEW, rawUri.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // startActivity() itself does not require package visibility. A missing handler is
        // handled as an ordinary failure rather than querying the user's installed apps.
        runCatching { context.startActivity(intent) }
    }

    private fun launchIntentScheme(rawUri: String, loadFallback: (String) -> Unit) {
        val parsed = runCatching { Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
            ?.takeIf(::isHttpUrl)

        parsed.component = null
        parsed.selector = null
        parsed.flags = parsed.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            ).inv()
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { context.startActivity(parsed) }.isSuccess) return
        fallback?.let(loadFallback)
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = runCatching { value.toUri().scheme }.getOrNull()
        return scheme.equals("http", true) || scheme.equals("https", true)
    }
}
