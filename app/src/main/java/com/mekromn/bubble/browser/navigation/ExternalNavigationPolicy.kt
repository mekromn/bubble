package com.mekromn.bubble.browser.navigation

import java.net.URI

sealed interface ExternalNavigationDecision {
    data object WebView : ExternalNavigationDecision
    data class ExternalApp(val uri: String) : ExternalNavigationDecision
    data class IntentScheme(val uri: String) : ExternalNavigationDecision
    data object Block : ExternalNavigationDecision
}

object ExternalNavigationPolicy {
    fun classify(rawUrl: String, hasUserGesture: Boolean): ExternalNavigationDecision {
        val scheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull()
            ?: rawUrl.substringBefore(':', missingDelimiterValue = "").lowercase()

        return when (scheme) {
            "http", "https", "about" -> ExternalNavigationDecision.WebView
            "mailto", "tel", "sms", "geo", "market" -> {
                if (hasUserGesture) ExternalNavigationDecision.ExternalApp(rawUrl)
                else ExternalNavigationDecision.Block
            }
            "intent" -> if (hasUserGesture) {
                ExternalNavigationDecision.IntentScheme(rawUrl)
            } else {
                ExternalNavigationDecision.Block
            }
            "file", "content", "javascript", "data" -> ExternalNavigationDecision.Block
            else -> ExternalNavigationDecision.Block
        }
    }
}
