package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun exactChatOriginOnly() {
        assertTrue(Policy.isChat("https://chatgpt.com/c/123"))
        assertTrue(Policy.isChat("https://CHATGPT.COM:443/"))
        listOf("http://chatgpt.com/", "https://chatgpt.com.evil.test/", "https://evil.test/chatgpt.com", "https://chatgpt.com:444/", "https://user@chatgpt.com/", "javascript:alert(1)").forEach { assertFalse(it,Policy.isChat(it)) }
    }
    @Test fun resolveBrowserAddressesAndSearch() {
        assertEquals("https://google.com", Policy.resolve("google.com"))
        assertEquals("https://example.com/?a=1",Policy.resolve("example.com/?a=1"))
        assertEquals("https://localhost:8080/path",Policy.resolve("localhost:8080/path"))
        assertEquals("http://127.0.0.1:8080/",Policy.resolve("http://127.0.0.1:8080/"))
        assertEquals(Policy.HOME,Policy.resolve("  "))
        assertTrue(Policy.resolve("bubble browser")!!.contains("q=bubble+browser"))
        listOf("javascript:alert(1)","file:///sdcard/private","intent://settings","data:text/html,hello").forEach{assertNull(it,Policy.resolve(it))}
    }
    @Test fun positionsAlwaysStayReachable() {
        assertEquals(10,Policy.coordinate(-2f,10,100))
        assertEquals(100,Policy.coordinate(2f,10,100))
        assertEquals(55,Policy.coordinate(Float.NaN,10,100))
        assertEquals(10,Policy.coordinate(.4f,10,-10))
    }
    @Test fun repeatedNativeCrashesStopRecoveringUntilResetOrCooldown() {
        val budget=RecoveryBudget()
        assertTrue(budget.allow(0));assertTrue(budget.allow(500));assertFalse(budget.allow(1000))
        assertTrue(budget.allow(61_000));assertTrue(budget.allow(61_100));assertFalse(budget.allow(61_200))
        budget.reset();assertTrue(budget.allow(61_300))
    }
}
