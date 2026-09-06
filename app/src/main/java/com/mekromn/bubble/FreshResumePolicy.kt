package com.mekromn.bubble

/**
 * A hibernated ChatGPT renderer is only a resource optimization; its last DOM snapshot is never
 * authoritative when the user comes back. ChatGPT tabs therefore resume with a fresh navigation.
 * Non-ChatGPT manual suspension keeps the existing browser-state behavior.
 */
internal object FreshResumePolicy {
    fun afterHibernate(url: String): Boolean = Policy.isChat(url)
    fun afterColdRestore(url: String): Boolean = Policy.isChat(url)
    fun shouldBypassSnapshot(url: String, freshRequired: Boolean): Boolean = freshRequired && Policy.isChat(url)
}
