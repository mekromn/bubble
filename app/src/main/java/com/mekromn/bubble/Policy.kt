package com.mekromn.bubble

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Pure policy: no Android objects, testable without an emulator. */
object Policy {
    const val HOME = "https://chatgpt.com/"
    fun isChat(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme.equals("https", true) && u.host.equals("chatgpt.com", true) &&
            (u.port == -1 || u.port == 443) && u.rawUserInfo == null
    }.getOrDefault(false)
    fun isWeb(url: String): Boolean = runCatching {
        val u = URI(url)
        (u.scheme.equals("https", true) || u.scheme.equals("http", true)) &&
            !u.host.isNullOrBlank() && u.rawUserInfo == null
    }.getOrDefault(false)
    fun resolve(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return HOME
        if (isWeb(text)) return text
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(text) &&
            !Regex("^[a-zA-Z0-9.-]+:\\d+([/?#].*)?$").matches(text)) return null
        if (!text.any(Char::isWhitespace) && (text.contains('.') || text.startsWith("localhost"))) {
            val candidate = "https://$text"
            if (isWeb(candidate)) return candidate
        }
        return "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
    }
    fun host(url: String): String = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
    fun coordinate(normalized: Float, min: Int, max: Int): Int {
        val n = if (normalized.isFinite()) normalized.coerceIn(0f, 1f) else 0.5f
        return min + ((max.coerceAtLeast(min) - min) * n).toInt()
    }
}

/** A real renderer crash cannot cause an unbounded recreate/crash loop. */
class RecoveryBudget {
    private val failures = ArrayDeque<Long>()
    fun allow(now: Long): Boolean {
        while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
        if (failures.size >= 2) return false
        failures.addLast(now)
        return true
    }
    fun reset() { failures.clear() }
}
