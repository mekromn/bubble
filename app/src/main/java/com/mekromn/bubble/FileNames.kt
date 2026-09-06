package com.mekromn.bubble

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Untrusted server/provider names are display names, never filesystem paths. */
internal object FileNames {
    fun safe(raw: String?, fallback: String = "download"): String {
        var name = raw.orEmpty().replace('\\', '/').substringAfterLast('/').filter {
            !it.isISOControl() && it !in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" && it !in ":*?\"<>|"
        }.trim().trim('.')
        if (name.isBlank()) name = fallback
        // Preserve the extension while bounding UTF-8 bytes (not just UTF-16 characters).
        if (name.toByteArray(Charsets.UTF_8).size > 200) {
            val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..16 }?.let { ".$it" }.orEmpty()
            var stem = if (extension.isEmpty()) name else name.dropLast(extension.length)
            while ((stem + extension).toByteArray(Charsets.UTF_8).size > 200 && stem.isNotEmpty()) stem = stem.dropLast(1)
            name = stem + extension
        }
        return name
    }
    fun download(uri: String, disposition: String?): String {
        val attrs = Regex("(?:^|;)\\s*([^=;]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))")
            .findAll(disposition.orEmpty()).associate { m ->
                m.groupValues[1].trim().lowercase(Locale.ROOT) to
                    (if (m.groups[2] != null) m.groupValues[2].replace("\\\"", "\"") else m.groupValues[3].trim())
            }
        val encoded = attrs["filename*"]?.let { value ->
            val parts = value.split('\'', limit = 3)
            if (parts.size == 3 && parts[0].equals("UTF-8", true)) runCatching {
                URLDecoder.decode(parts[2].replace("+", "%2B"), "UTF-8")
            }.getOrNull() else null
        }
        val fromUrl = runCatching {
            val path = URI(uri).rawPath.orEmpty().substringAfterLast('/')
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        }.getOrNull()
        return safe(encoded?.takeIf { it.isNotBlank() } ?: attrs["filename"]?.takeIf { it.isNotBlank() } ?: fromUrl)
    }
    fun mime(raw: String?): String {
        val value = raw.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return if (Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(value)) value else "application/octet-stream"
    }
}
