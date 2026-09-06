package com.mekromn.bubble

/**
 * A hibernated ChatGPT renderer is only a resource optimization; its last DOM/session snapshot is
 * never authoritative when the user comes back. Every newly-created ChatGPT GeckoSession therefore
 * navigates the canonical URL fresh. Non-ChatGPT browser tabs retain normal session-state restore.
 */
internal object FreshResumePolicy {
    fun requiresFreshNavigation(url: String): Boolean = Policy.isChat(url)
    fun shouldRestoreSnapshot(url: String, hasSnapshot: Boolean): Boolean = hasSnapshot && !requiresFreshNavigation(url)
}
