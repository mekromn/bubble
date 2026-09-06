package com.mekromn.bubble

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Test-APK-only SAF provider. No _data column; the browser must use the granted stream. */
class FixtureDocuments : DocumentsProvider() {
    private lateinit var dir: File
    override fun onCreate(): Boolean {
        dir = File(context!!.filesDir, "test-documents").apply { mkdirs() }
        File(dir, "attach-one.txt").writeBytes("Bubble attachment UTF-8 ✓\n".toByteArray())
        File(dir, "attach-two.bin").writeBytes(ByteArray(1024) { (it * 17).toByte() })
        return true
    }
    private fun cursor(columns: Array<out String>, values: Map<String, Any?>) = MatrixCursor(columns).apply {
        addRow(columns.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        return cursor(columns, mapOf(Root.COLUMN_ROOT_ID to "fixture", Root.COLUMN_DOCUMENT_ID to "root", Root.COLUMN_TITLE to "Bubble file tests",
            Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY), Root.COLUMN_MIME_TYPES to "*/*", Root.COLUMN_AVAILABLE_BYTES to 1_000_000_000L))
    }
    private fun file(id: String): File {
        require(id != "root" && !id.contains('/') && !id.contains('\\') && id != "." && id != "..")
        return File(dir, id)
    }
    private fun columns(projection: Array<out String>?) = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    private fun values(id: String): Map<String, Any?> {
        val root = id == "root"; val f = if (root) dir else file(id)
        return mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to if (root) "Bubble file tests" else id,
            Document.COLUMN_MIME_TYPE to if (root) Document.MIME_TYPE_DIR else if (id.endsWith(".txt")) "text/plain" else "application/octet-stream",
            Document.COLUMN_FLAGS to if (root) Document.FLAG_DIR_SUPPORTS_CREATE else (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE),
            Document.COLUMN_SIZE to if (root) 0L else f.length(), Document.COLUMN_LAST_MODIFIED to f.lastModified())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(columns(projection), values(documentId))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "root")
        val cols = columns(projection)
        return MatrixCursor(cols).apply { dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file -> val row = values(file.name); addRow(cols.map { row[it] }.toTypedArray()) } }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?) = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root")
        val name = displayName.replace('/', '_').replace('\\', '_').take(200)
        var candidate = name; var index = 1
        while (file(candidate).exists()) candidate = "${index++}-$name"
        check(file(candidate).createNewFile()); return candidate
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
}
