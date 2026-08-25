package com.mekromn.bubble.browser.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri

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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
        }
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

        val resolvable = parsed.resolveActivity(context.packageManager) != null
        if (resolvable && runCatching { context.startActivity(parsed) }.isSuccess) return
        fallback?.let(loadFallback)
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = runCatching { Uri.parse(value).scheme }.getOrNull()
        return scheme.equals("http", true) || scheme.equals("https", true)
    }
}
