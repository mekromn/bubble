package com.mekromn.bubble.browser.engine

import com.mekromn.bubble.browser.session.UserAgentMode

object UserAgentPolicy {
    private val chromeVersionRegex = Regex("Chrome/([0-9]+)(?:\\.([0-9.]+))?")

    data class ChromeVersion(
        val major: String,
        val full: String,
    )

    fun chromeVersion(systemUserAgent: String, webViewPackageVersion: String?): ChromeVersion {
        val fromPackage = webViewPackageVersion
            ?.substringBefore('-')
            ?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) }
        val fromUa = chromeVersionRegex.find(systemUserAgent)?.let { match ->
            val major = match.groupValues[1]
            val suffix = match.groupValues.getOrNull(2).orEmpty()
            if (suffix.isBlank()) major else "$major.$suffix"
        }
        val full = fromPackage ?: fromUa ?: "120.0.0.0"
        return ChromeVersion(
            major = full.substringBefore('.'),
            full = full,
        )
    }

    fun userAgentString(
        systemUserAgent: String,
        webViewPackageVersion: String?,
        mode: UserAgentMode,
    ): String? {
        if (mode == UserAgentMode.SYSTEM) return null
        val version = chromeVersion(systemUserAgent, webViewPackageVersion)
        val reduced = "${version.major}.0.0.0"
        return when (mode) {
            UserAgentMode.MOBILE -> "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$reduced Mobile Safari/537.36"
            UserAgentMode.DESKTOP -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$reduced Safari/537.36"
            UserAgentMode.SYSTEM -> null
        }
    }
}
