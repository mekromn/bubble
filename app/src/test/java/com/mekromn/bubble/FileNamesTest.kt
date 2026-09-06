package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class FileNamesTest {
    @Test fun untrustedNamesCannotEscapeTheirDirectory() {
        assertEquals("report.pdf", FileNames.safe("../../report.pdf"))
        assertEquals("report.pdf", FileNames.safe("C:\\folder\\report.pdf"))
        assertEquals("download", FileNames.safe("\u0000..."))
        assertEquals("report.pdf", FileNames.safe("report\u202e.pdf"))
        assertTrue(FileNames.safe("測".repeat(100) + ".pdf").toByteArray().size <= 200)
        assertTrue(FileNames.safe("測".repeat(100) + ".pdf").endsWith(".pdf"))
    }
    @Test fun encodedDispositionPreservesUnicodeSpacesAndLiteralPlus() {
        assertEquals("my report.txt", FileNames.download("https://example.test/f", "attachment; filename*=UTF-8''my%20report.txt"))
        assertEquals("résumé+.pdf", FileNames.download("https://example.test/f", "attachment; filename=old.pdf; filename*=UTF-8''r%C3%A9sum%C3%A9+.pdf"))
        assertEquals("report.txt", FileNames.download("https://example.test/f", "attachment; filename=\"report.txt\""))
        assertEquals("a+b.txt", FileNames.download("https://example.test/a+b.txt?secret=x", null))
        assertEquals("application/octet-stream", FileNames.mime("bad\r\nheader"))
        assertEquals("text/plain", FileNames.mime("text/plain; charset=UTF-8"))
    }
}
