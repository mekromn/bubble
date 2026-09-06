package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class FreshResumePolicyTest {
    @Test fun chatgptNeverRestoresSuspendedSnapshot() {
        assertTrue(FreshResumePolicy.requiresFreshNavigation("https://chatgpt.com/"))
        assertTrue(FreshResumePolicy.requiresFreshNavigation("https://chatgpt.com/c/example"))
        assertFalse(FreshResumePolicy.shouldRestoreSnapshot("https://chatgpt.com/c/example", true))
    }

    @Test fun ordinaryWebTabsCanStillRestoreNormalBrowserState() {
        assertFalse(FreshResumePolicy.requiresFreshNavigation("https://example.com/page"))
        assertTrue(FreshResumePolicy.shouldRestoreSnapshot("https://example.com/page", true))
        assertFalse(FreshResumePolicy.shouldRestoreSnapshot("https://example.com/page", false))
    }
}
