package com.mekromn.bubble.browser.navigation

import java.net.URI

object SharedUrlExtractor {
    private val webUrl = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
    private val trailingSharePunctuation = charArrayOf('.', ',', ';', ')', ']', '}', '!')

    fun extract(text: CharSequence?): String? {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null

        val candidate = if (looksLikeWebUrl(raw)) {
            raw
        } else {
            webUrl.find(raw)?.value ?: return null
        }.trimEnd(*trailingSharePunctuation)

        return candidate.takeIf(::looksLikeWebUrl)
    }

    private fun looksLikeWebUrl(value: String): Boolean {
        return runCatching {
            val uri = URI(value)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
