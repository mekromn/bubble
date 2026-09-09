package com.mekromn.bubble

import java.net.URI
import java.util.Locale

/**
 * Direct GitHub assets are frequently opened with target=_blank even though they are downloads, not
 * pages. Reusing the current GeckoSession for only these well-known download endpoints lets Gecko's
 * normal onExternalResponse path consume the original authenticated response without creating a
 * visible/retained Bubble tab. Ordinary GitHub pages and raw source links keep normal tab behavior.
 */
internal object DownloadPopupPolicy {
    fun shouldReuseOpener(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val path = uri.rawPath.orEmpty()
        when (host) {
            "github.com" -> path.contains("/releases/download/") ||
                (path.contains("/archive/refs/") && archivePath(path))
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com" -> true
            else -> false
        }
    }.getOrDefault(false)

    private fun archivePath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar")
    }
}
