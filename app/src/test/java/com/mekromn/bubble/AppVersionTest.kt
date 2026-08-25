package com.mekromn.bubble

import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun versionNameIsSemantic() {
        assertTrue(AppVersion.NAME.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }
}
