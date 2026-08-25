package com.mekromn.bubble.browser.navigation

import com.mekromn.bubble.data.settings.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationResolverTest {
    @Test
    fun bareDomainDefaultsToHttps() {
        val result = NavigationResolver.resolve("example.com/path", SearchEngine.GOOGLE)
        val web = result as ResolvedNavigation.Web
        assertEquals("https://example.com/path", web.url)
        assertFalse(web.insecure)
    }

    @Test
    fun httpRemainsNavigableAndMarkedInsecure() {
        val result = NavigationResolver.resolve("http://example.com", SearchEngine.GOOGLE)
        val web = result as ResolvedNavigation.Web
        assertEquals("http://example.com", web.url)
        assertTrue(web.insecure)
    }

    @Test
    fun textBecomesEncodedSearch() {
        val result = NavigationResolver.resolve("bubble browser heads", SearchEngine.DUCK_DUCK_GO)
        val web = result as ResolvedNavigation.Web
        assertEquals("https://duckduckgo.com/?q=bubble+browser+heads", web.url)
    }

    @Test
    fun arbitrarySchemeIsNotExecuted() {
        val result = NavigationResolver.resolve("javascript:alert(1)", SearchEngine.GOOGLE)
        assertEquals(ResolvedNavigation.UnsupportedScheme("javascript"), result)
    }
}
