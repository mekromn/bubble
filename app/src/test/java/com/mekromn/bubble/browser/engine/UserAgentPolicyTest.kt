package com.mekromn.bubble.browser.engine

import com.mekromn.bubble.browser.session.UserAgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UserAgentPolicyTest {
    private val webViewUa = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro XL Build/BP2A; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.0.0 Mobile Safari/537.36"

    @Test
    fun mobileMode_usesReducedChromeMobileShape_withoutWebViewMarkers() {
        val ua = UserAgentPolicy.userAgentString(webViewUa, "147.0.7654.12", UserAgentMode.MOBILE)!!
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/147.0.0.0 Mobile Safari/537.36",
            ua,
        )
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
    }

    @Test
    fun desktopMode_usesChromeDesktopShape() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/147.0.0.0 Safari/537.36",
            UserAgentPolicy.userAgentString(webViewUa, "147.0.7654.12", UserAgentMode.DESKTOP),
        )
    }

    @Test
    fun systemMode_leavesUserAgentUnmodified() {
        assertNull(UserAgentPolicy.userAgentString(webViewUa, null, UserAgentMode.SYSTEM))
    }
}
