package com.mekromn.bubble.browser.navigation

import com.mekromn.bubble.data.settings.SearchEngine
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface ResolvedNavigation {
    data class Web(val url: String, val insecure: Boolean) : ResolvedNavigation
    data class UnsupportedScheme(val scheme: String) : ResolvedNavigation
}

object NavigationResolver {
    private val domainLike = Regex(
        pattern = "^(localhost|(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}|(?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d+)?(?:/.*)?$",
    )

    fun resolve(rawInput: String, searchEngine: SearchEngine): ResolvedNavigation {
        val input = rawInput.trim()
        if (input.isBlank()) return ResolvedNavigation.Web(NEW_TAB_URL, insecure = false)

        val explicitScheme = runCatching { URI(input).scheme?.lowercase() }.getOrNull()
        if (explicitScheme != null) {
            return when (explicitScheme) {
                "http" -> ResolvedNavigation.Web(input, insecure = true)
                "https" -> ResolvedNavigation.Web(input, insecure = false)
                "about" -> if (input == NEW_TAB_URL) {
                    ResolvedNavigation.Web(input, insecure = false)
                } else {
                    ResolvedNavigation.UnsupportedScheme(explicitScheme)
                }
                else -> ResolvedNavigation.UnsupportedScheme(explicitScheme)
            }
        }

        if (domainLike.matches(input)) {
            return ResolvedNavigation.Web("https://$input", insecure = false)
        }

        val query = URLEncoder.encode(input, StandardCharsets.UTF_8.name())
        return ResolvedNavigation.Web(searchEngine.queryTemplate.format(query), insecure = false)
    }

    const val NEW_TAB_URL = "about:blank"
}
