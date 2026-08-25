package com.mekromn.bubble.browser.session

import java.util.UUID

@JvmInline
value class TabId(val value: String) {
    init {
        require(value.isNotBlank()) { "TabId must not be blank" }
    }

    companion object {
        fun newId(): TabId = TabId(UUID.randomUUID().toString())
    }
}

enum class PresentationState {
    BROWSER,
    HEAD,
}

enum class ResidencyState {
    ACTIVE,
    WARM,
    SAVED,
    HIBERNATED,
    RECOVERING,
}

enum class UserAgentMode {
    /** Chrome-compatible mobile identity. This is Bubble's default. */
    MOBILE,

    /** Chrome-compatible desktop identity for sites that hide desktop UI on mobile. */
    DESKTOP,

    /** Unmodified Android System WebView user-agent and client-hint identity. */
    SYSTEM,
}

data class Tab(
    val id: TabId,
    val profileId: String = NORMAL_PROFILE_ID,
    val createdAt: Long,
    val lastActivatedAt: Long,
    val sortIndex: Long,
    val lastCommittedUrl: String,
    val title: String = "New tab",
    val faviconKey: String? = null,
    val presentationState: PresentationState = PresentationState.BROWSER,
    val residencyState: ResidencyState = ResidencyState.HIBERNATED,
    val pinned: Boolean = false,
    val keepRendererAlive: Boolean = false,
    val isPrivate: Boolean = false,
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
    val zoomPercent: Int = DEFAULT_ZOOM_PERCENT,
    val groupId: String? = null,
    val restoreStateKey: String? = null,
    val crashRecoveryCount: Int = 0,
    val selected: Boolean = false,
) {
    init {
        require(zoomPercent in MIN_ZOOM_PERCENT..MAX_ZOOM_PERCENT)
        require(!isPrivate || profileId != NORMAL_PROFILE_ID) {
            "Private tabs must never use the normal profile identity"
        }
    }

    companion object {
        const val NORMAL_PROFILE_ID = "normal"
        const val DEFAULT_ZOOM_PERCENT = 100
        const val MIN_ZOOM_PERCENT = 25
        const val MAX_ZOOM_PERCENT = 500
    }
}
