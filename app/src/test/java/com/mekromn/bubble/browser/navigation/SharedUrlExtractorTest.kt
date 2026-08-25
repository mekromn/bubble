package com.mekromn.bubble.browser.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedUrlExtractorTest {
    @Test
    fun rawUrl_isAccepted() {
        assertEquals(
            "https://example.com/path?q=1",
            SharedUrlExtractor.extract("https://example.com/path?q=1"),
        )
    }

    @Test
    fun titleAndUrl_extractsFirstWebLink() {
        assertEquals(
            "https://example.com/story",
            SharedUrlExtractor.extract("Interesting story https://example.com/story"),
        )
    }

    @Test
    fun proseWithoutUrl_returnsNull() {
        assertNull(SharedUrlExtractor.extract("this is not a URL"))
    }
}
