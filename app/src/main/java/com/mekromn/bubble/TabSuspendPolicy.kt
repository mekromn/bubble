package com.mekromn.bubble

/** Resource policy is intentionally conservative: Bubble only auto-suspends ChatGPT tabs
 * because its exact-origin reply monitor can tell us when those tabs are actually working.
 * Other sites keep the previous always-active behavior unless the user suspends them manually.
 * A user-forced keep-alive opt-out wins over automatic suspension, but never over an explicit
 * manual suspend command. */
internal object TabSuspendPolicy {
    const val MANUAL_MESSAGE = "Tab suspended to save resources. Tap to resume."

    fun automatic(isChat: Boolean, selectedVisible: Boolean, generating: Boolean,
        loading: Boolean, forceKeepAlive: Boolean): Boolean =
        isChat && !forceKeepAlive && !selectedVisible && !generating && !loading

    fun shouldSuspend(manual: Boolean, forceKeepAlive: Boolean, isChat: Boolean,
        selectedVisible: Boolean, generating: Boolean, loading: Boolean): Boolean =
        manual || automatic(isChat, selectedVisible, generating, loading, forceKeepAlive)

    fun canManualSuspend(generating: Boolean, loading: Boolean, fileUiBusy: Boolean): Boolean =
        !generating && !loading && !fileUiBusy
}
