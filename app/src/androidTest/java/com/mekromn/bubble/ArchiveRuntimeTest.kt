package com.mekromn.bubble

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun streamsMultipleFilesIntoOneZipWithCollisionHandling() {
        val root = File(context.cacheDir, "archive-runtime-${System.nanoTime()}").apply { mkdirs() }
        try {
            val one = File(root, "one.txt").apply { writeText("alpha\n".repeat(2048)) }
            val two = File(root, "two.txt").apply { writeText("beta\n".repeat(1536)) }
            val out = File(root, "bundle.zip")
            var lastProgress: ArchiveProgress? = null
            val result = ArchiveEngine.createZip(
                context,
                listOf(
                    ArchiveSource(Uri.fromFile(one), "same.txt", one.length()),
                    ArchiveSource(Uri.fromFile(two), "same.txt", two.length())
                ),
                out,
                ArchiveCompression.BALANCED,
                preservePaths = false,
                job = ArchiveJob()
            ) { lastProgress = it }
            assertEquals(out.absolutePath, result.absolutePath)
            assertTrue(out.isFile && out.length() > 0)
            assertEquals(2, lastProgress?.fileCount)
            assertEquals(one.length() + two.length(), lastProgress?.bytesProcessed)

            val entries = linkedMapOf<String, String>()
            ZipInputStream(out.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            assertEquals(listOf("same.txt", "same (2).txt"), entries.keys.toList())
            assertEquals(one.readText(), entries["same.txt"])
            assertEquals(two.readText(), entries["same (2).txt"])
        } finally { root.deleteRecursively() }
    }

    @Test fun preservesRequestedRelativePathsWithoutTraversal() {
        val root = File(context.cacheDir, "archive-path-runtime-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(root, "payload.bin").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
            val out = File(root, "paths.zip")
            ArchiveEngine.createZip(
                context,
                listOf(ArchiveSource(Uri.fromFile(file), "payload.bin", file.length(), "Docs/../Safe")),
                out,
                ArchiveCompression.STORE,
                preservePaths = true,
                job = ArchiveJob()
            )
            ZipInputStream(out.inputStream()).use { zip ->
                val entry = zip.nextEntry ?: fail("Missing ZIP entry") as java.util.zip.ZipEntry
                assertEquals("Docs/Safe/payload.bin", entry.name)
                assertArrayEquals(file.readBytes(), zip.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
