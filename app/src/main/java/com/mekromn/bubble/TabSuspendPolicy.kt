package com.mekromn.bubble

/** Resource policy is intentionally conservative: Bubble only auto-suspends ChatGPT tabs
 * because its exact-origin reply monitor can tell us when those tabs are actually working.
 * Other sites keep the previous always-active behavior unless the user suspends them manually. */
internal object TabSuspendPolicy {
    const val MANUAL_MESSAGE = "Tab suspended to save resources. Tap to resume."

    fun automatic(isChat: Boolean, selectedVisible: Boolean, generating: Boolean, loading: Boolean): Boolean =
        isChat && !selectedVisible && !generating && !loading

    fun shouldSuspend(manual: Boolean, isChat: Boolean, selectedVisible: Boolean,
        generating: Boolean, loading: Boolean): Boolean =
        manual || automatic(isChat, selectedVisible, generating, loading)

    fun canManualSuspend(generating: Boolean, loading: Boolean, fileUiBusy: Boolean): Boolean =
        !generating && !loading && !fileUiBusy
}
