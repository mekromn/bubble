package com.mekromn.bubble

import java.util.UUID

/** A profile is a persistent Gecko storage container, not a separate Android process. */
internal data class BrowserProfile(val id: String, val name: String)

internal object ProfilePolicy {
    // This EXACT context was used by 0.7.1. Changing it would appear to log existing users out.
    const val DEFAULT_ID = "normal"
    private val generatedId = Regex("bubble-profile-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun defaults() = listOf(BrowserProfile(DEFAULT_ID, "Default"))
    fun newId(): String = "bubble-profile-${UUID.randomUUID()}"
    fun validId(id: String): Boolean = id == DEFAULT_ID || generatedId.matches(id)
    fun name(raw: String): String = raw.filterNot { it.isISOControl() }.trim().take(60)
    fun nameProblem(raw: String, profiles: List<BrowserProfile>, except: String? = null): String? {
        val value = name(raw)
        return when {
            value.isEmpty() -> "Enter a profile name."
            profiles.any { it.id != except && it.name.equals(value, ignoreCase = true) } -> "That profile name is already in use."
            else -> null
        }
    }
    /** Preserve unknown-but-valid container IDs rather than silently use another account. */
    fun restore(saved: List<BrowserProfile>, references: List<String>): List<BrowserProfile> {
        val map = LinkedHashMap<String, BrowserProfile>()
        for (profile in saved) {
            require(validId(profile.id) && name(profile.name).isNotEmpty())
            require(map.put(profile.id, profile.copy(name = name(profile.name))) == null)
        }
        if (DEFAULT_ID !in map) map[DEFAULT_ID] = defaults().single()
        for (id in references) {
            require(validId(id))
            if (id !in map) map[id] = BrowserProfile(id, "Recovered profile ${id.takeLast(8)}")
        }
        return map.values.toList()
    }
}
