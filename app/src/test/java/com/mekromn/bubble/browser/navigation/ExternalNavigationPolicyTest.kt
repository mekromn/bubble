package com.mekromn.bubble.browser.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalNavigationPolicyTest {
    @Test
    fun webUrlsRemainInsideWebView() {
        assertEquals(
            ExternalNavigationDecision.WebView,
            ExternalNavigationPolicy.classify("https://example.com", hasUserGesture = false),
        )
    }

    @Test
    fun telRequiresUserGesture() {
        assertEquals(
            ExternalNavigationDecision.Block,
            ExternalNavigationPolicy.classify("tel:+15551212", hasUserGesture = false),
        )
        assertEquals(
            ExternalNavigationDecision.ExternalApp("tel:+15551212"),
            ExternalNavigationPolicy.classify("tel:+15551212", hasUserGesture = true),
        )
    }

    @Test
    fun fileAndUnknownSchemesAreBlocked() {
        assertEquals(
            ExternalNavigationDecision.Block,
            ExternalNavigationPolicy.classify("file:///sdcard/secret", hasUserGesture = true),
        )
        assertEquals(
            ExternalNavigationDecision.Block,
            ExternalNavigationPolicy.classify("random-app://thing", hasUserGesture = true),
        )
    }
}
