package com.mekromn.bubble

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.text.DecimalFormat

/**
 * "Select and Compress" is intentionally NOT a file browser.
 *
 * Bubble immediately delegates selection to Android's stock ACTION_OPEN_DOCUMENT UI so the user gets
 * the system picker, every installed DocumentsProvider, its normal sorting/search UI and its normal
 * provider permissions. Only after Android returns the selected document URIs does Bubble appear and
 * show the existing archive/compression controls.
 *
 * This keeps Bubble out of storage browsing entirely: no duplicate browser, no Providers button and
 * no All-files-access dependency in this flow. Selected documents are streamed from their provider
 * directly into the archive; they are never copied to a temporary input directory first.
 */
class ArchivePickerActivity : Activity() {
    private val selected = LinkedHashSet<Uri>()
    private val sourceCache = LinkedHashMap<Uri, ArchiveSource>()
    private var pickerLaunched = false
    private var uiBuilt = false
    private var busy = false
    private var archiveJob: ArchiveJob? = null

    private lateinit var selectedState: TextView
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var filename: EditText
    private lateinit var compression: Spinner
    private lateinit var preserve: CheckBox
    private lateinit var archiveSingle: CheckBox
    private lateinit var saveCopy: CheckBox
    private lateinit var action: TextView

    private val tabId get() = intent.getStringExtra(EXTRA_TAB_ID).orEmpty()
    private val requestId get() = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
    private val internalUpload get() = tabId.isNotBlank() && requestId.isNotBlank()
    private val shareMode get() = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
    private val prefs by lazy { getSharedPreferences("archive-picker-v1", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        ArchiveCache.cleanup(this)

        pickerLaunched = state?.getBoolean(STATE_PICKER_LAUNCHED, false) == true
        state?.getStringArrayList(STATE_URIS)?.forEach { encoded ->
            runCatching { Uri.parse(encoded) }.getOrNull()?.let(::addSelected)
        }
        collectIncomingShare()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        when {
            selected.isNotEmpty() -> {
                buildCompressionUi(state)
                updateSelection()
            }
            shareMode -> {
                toast("No readable files were shared with Select and Compress.")
                cancel()
            }
            !pickerLaunched -> launchStockPicker()
            else -> showTransparentWaitingSurface()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PICKER_LAUNCHED, pickerLaunched)
        outState.putStringArrayList(STATE_URIS, ArrayList(selected.map(Uri::toString)))
        if (uiBuilt) {
            outState.putString(STATE_FILENAME, filename.text.toString())
            outState.putInt(STATE_COMPRESSION, compression.selectedItemPosition)
            outState.putBoolean(STATE_PATHS, preserve.isChecked)
            outState.putBoolean(STATE_SINGLE, archiveSingle.isChecked)
            outState.putBoolean(STATE_SAVE_COPY, saveCopy.isChecked)
        }
    }

    /** Launch Android's own Files/DocumentsUI immediately. Bubble never renders a browsing list. */
    private fun launchStockPicker() {
        if (busy || pickerLaunched) return
        pickerLaunched = true
        showTransparentWaitingSurface()
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(pick, PICK_DOCUMENTS)
        } catch (_: RuntimeException) {
            pickerLaunched = false
            toast("Android could not open the system document picker.")
            cancel()
        }
    }

    /**
     * The prompt theme is translucent, so this normally never paints before DocumentsUI covers it.
     * Keeping a tiny transparent root avoids flashing the old Bubble picker during the hand-off.
     */
    private fun showTransparentWaitingSurface() {
        if (uiBuilt) return
        setContentView(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) })
    }

    @Deprecated("System document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DOCUMENTS) return
        pickerLaunched = false
        if (resultCode != RESULT_OK) {
            cancel()
            return
        }

        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::addSelected)
        }
        data?.data?.let(::addSelected)
        if (selected.isEmpty()) {
            toast("Android returned no readable documents.")
            cancel()
            return
        }

        buildCompressionUi(null)
        updateSelection()
    }

    private fun addSelected(uri: Uri) {
        if (!selected.add(uri)) return
        sourceCache[uri] = ArchiveEngine.source(this, uri, providerRelativePath(uri))
    }

    /**
     * Preserve the old "Paths" option without requiring raw filesystem access. Android's external
     * storage DocumentsProvider exposes document IDs like primary:Download/folder/file.ext; when that
     * trustworthy relative path exists we preserve its parent. Cloud-provider opaque IDs stay flat.
     */
    private fun providerRelativePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val relative = documentId.substringAfter(':', "").trim('/')
        val parent = relative.substringBeforeLast('/', "").trim('/')
        return parent.takeIf { it.isNotBlank() }
    }

    private fun sources(): List<ArchiveSource> = selected.map { uri ->
        sourceCache.getOrPut(uri) { ArchiveEngine.source(this, uri, providerRelativePath(uri)) }
    }

    private fun collectIncomingShare() {
        if (!shareMode) return
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addSelected)
        }
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(::addSelected)
        }
    }

    private fun buildCompressionUi(state: Bundle?) {
        if (uiBuilt) return
        uiBuilt = true

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(14), d(16), d(14))
            background = Ui.shape(this@ArchivePickerActivity, Ui.SURFACE, 24f, Ui.LINE)
        }

        panel.addView(Ui.text(this, actionLabel(), 18f, Ui.TEXT, true).apply {
            setPadding(d(4), d(2), d(4), d(4))
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(Ui.text(this,
            "Android selected the files. Choose how Bubble should package them before returning the attachment.",
            11f, Ui.MUTED).apply { setPadding(d(4), 0, d(4), d(8)) }, LinearLayout.LayoutParams(-1, -2))

        selectedState = Ui.text(this, "", 12f, Ui.TEXT, true).apply {
            maxLines = 3
            setPadding(d(10), d(8), d(10), d(8))
            background = Ui.shape(this@ArchivePickerActivity, Ui.BG, 14f)
        }
        panel.addView(selectedState, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, d(8)) })

        filename = EditText(this).apply {
            setSingleLine(true)
            setText(state?.getString(STATE_FILENAME) ?: ArchiveEngine.defaultName())
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            hint = "Archive filename"
            background = Ui.shape(this@ArchivePickerActivity, Ui.SURFACE_HIGH, 14f)
            setPadding(d(12), 0, d(12), 0)
        }
        panel.addView(filename, LinearLayout.LayoutParams(-1, d(46)).apply { setMargins(0, 0, 0, d(6)) })

        val options = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        compression = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ArchivePickerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ArchiveCompression.values().map { it.label }
            )
            val remembered = ArchiveCompression.values().indexOfFirst {
                it.name == prefs.getString("compression", ArchiveCompression.BALANCED.name)
            }.coerceAtLeast(0)
            setSelection(state?.getInt(STATE_COMPRESSION, remembered) ?: remembered)
        }
        options.addView(compression, LinearLayout.LayoutParams(0, d(48), 1f))
        preserve = CheckBox(this).apply {
            text = "Paths"
            setTextColor(Ui.TEXT)
            isChecked = state?.getBoolean(STATE_PATHS, prefs.getBoolean("paths", false))
                ?: prefs.getBoolean("paths", false)
        }
        archiveSingle = CheckBox(this).apply {
            text = "Archive 1"
            setTextColor(Ui.TEXT)
            isChecked = state?.getBoolean(STATE_SINGLE, prefs.getBoolean("single", true))
                ?: prefs.getBoolean("single", true)
        }
        options.addView(preserve, LinearLayout.LayoutParams(-2, d(48)))
        options.addView(archiveSingle, LinearLayout.LayoutParams(-2, d(48)))
        panel.addView(options, LinearLayout.LayoutParams(-1, -2))

        saveCopy = CheckBox(this).apply {
            text = "Save a copy to Downloads/Bubble"
            setTextColor(Ui.TEXT)
            isChecked = state?.getBoolean(STATE_SAVE_COPY, prefs.getBoolean("saveCopy", false))
                ?: prefs.getBoolean("saveCopy", false)
        }
        panel.addView(saveCopy, LinearLayout.LayoutParams(-1, d(42)))

        val requested = intent.type.orEmpty()
        if (!internalUpload && requested.isNotBlank() && requested != "*/*" &&
            requested != "application/zip" && !shareMode) {
            panel.addView(Ui.text(
                this,
                "The requesting app asked for $requested. Returning a ZIP may be rejected by that app.",
                11f,
                0xffffc66d.toInt()
            ).apply { setPadding(d(8), d(5), d(8), d(5)) }, LinearLayout.LayoutParams(-1, -2))
        }

        progressText = Ui.text(this, "", 11f, Ui.MUTED).apply {
            visibility = View.GONE
            setPadding(d(8), d(5), d(8), d(3))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        panel.addView(progressText, LinearLayout.LayoutParams(-1, -2))
        panel.addView(progress, LinearLayout.LayoutParams(-1, d(4)))

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(8), 0, 0)
        }
        bottom.addView(button("Cancel", "Cancel") { cancel() }, LinearLayout.LayoutParams(d(92), d(50)))
        action = button(actionLabel(), actionLabel()) { compressOrReturn() }
        bottom.addView(action, LinearLayout.LayoutParams(0, d(50), 1f).apply { marginStart = d(6) })
        panel.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(d(10), d(10), d(10), d(10))
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(safe.bottom, keyboard.bottom)
            panel.setPadding(d(16) + safe.left, d(14), d(16) + safe.right, d(14) + bottomInset)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun actionLabel() = when {
        internalUpload -> "Compress & Attach"
        shareMode -> "Compress & Share"
        else -> "Compress & Send"
    }

    private fun updateSelection() {
        if (!uiBuilt) return
        val items = sources()
        val knownBytes = items.filter { it.size > 0L }.sumOf { it.size }
        val names = items.take(3).joinToString(", ") { it.displayName }
        val more = if (items.size > 3) " +${items.size - 3} more" else ""
        selectedState.text = buildString {
            append(items.size).append(" selected")
            if (knownBytes > 0L) append(" · ").append(human(knownBytes))
            if (names.isNotBlank()) append('\n').append(names).append(more)
        }
        action.isEnabled = items.isNotEmpty() && !busy
        action.alpha = if (action.isEnabled) 1f else .45f
    }

    private fun compressOrReturn() {
        if (busy) return
        val sources = sources()
        if (sources.isEmpty()) {
            toast("No readable files selected.")
            return
        }
        persistOptions()
        if (sources.size == 1 && !archiveSingle.isChecked) {
            returnSingle(sources.first())
            return
        }

        busy = true
        updateSelection()
        setControlsBusy(true)
        val job = ArchiveJob()
        archiveJob = job
        val selectedCompression = ArchiveCompression.values().getOrElse(compression.selectedItemPosition) {
            ArchiveCompression.BALANCED
        }
        val preservePaths = preserve.isChecked
        val keepCopy = saveCopy.isChecked
        val out = if (internalUpload) {
            UploadStaging.archiveOutput(this, tabId, requestId, filename.text.toString())
        } else {
            ArchiveCache.output(this, filename.text.toString())
        }
        UploadStaging.io.execute {
            try {
                val archive = ArchiveEngine.createZip(
                    this,
                    sources,
                    out,
                    selectedCompression,
                    preservePaths,
                    job
                ) { p -> runOnUiThread { showProgress(p) } }
                val copyFailure = if (keepCopy) runCatching { savePublicCopy(archive) }.exceptionOrNull() else null
                runOnUiThread {
                    if (copyFailure != null) toast("Archive created, but the Downloads copy could not be saved.")
                    completeArchive(archive)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    busy = false
                    archiveJob = null
                    setControlsBusy(false)
                    updateSelection()
                    if (!job.cancelled.get()) {
                        toast("Could not create the archive. Check free space and selected file access.")
                    }
                }
            }
        }
    }

    private fun savePublicCopy(file: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bubble")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create Downloads item")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    file.inputStream().buffered().use { it.copyTo(output, 128 * 1024) }
                } ?: throw IOException("Could not open Downloads output")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Bubble"
            ).apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    private fun returnSingle(source: ArchiveSource) {
        persistOptions()
        busy = true
        setControlsBusy(true)
        if (internalUpload && source.uri.scheme == "file") {
            setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, source.uri.path))
            finish()
            return
        }
        if (internalUpload && source.uri.scheme == "content") {
            UploadStaging.io.execute {
                val staged = runCatching {
                    UploadStaging.prepare(this, tabId, requestId, listOf(source.uri), UploadStaging.Job())
                }.getOrElse { emptyList() }
                runOnUiThread {
                    val path = staged.firstOrNull()?.path
                    if (path == null) {
                        busy = false
                        setControlsBusy(false)
                        toast("Could not read that file.")
                    } else {
                        setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, path))
                        finish()
                    }
                }
            }
            return
        }

        val uri = when (source.uri.scheme) {
            "content" -> source.uri
            "file" -> runCatching {
                FileProvider.getUriForFile(this, "$packageName.archives", File(source.uri.path.orEmpty()))
            }.getOrNull()
            else -> null
        }
        if (uri == null) {
            busy = false
            setControlsBusy(false)
            toast("Could not share that file.")
            return
        }
        if (shareMode) {
            share(uri, contentResolver.getType(uri) ?: "application/octet-stream")
        } else {
            setResult(
                RESULT_OK,
                Intent().setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            finish()
        }
    }

    private fun showProgress(p: ArchiveProgress) {
        progress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "${p.fileIndex}/${p.fileCount} · ${p.currentName} · ${human(p.bytesProcessed)}"
        progress.progress = if (p.totalBytes > 0) {
            ((p.bytesProcessed.toDouble() / p.totalBytes) * progress.max).toInt().coerceIn(0, progress.max)
        } else 0
    }

    private fun completeArchive(file: File) {
        archiveJob = null
        progress.progress = progress.max
        progressText.text = "Done · ${human(file.length())}"
        if (internalUpload) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, file.absolutePath))
            finish()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.archives", file)
        if (shareMode) {
            share(uri, "application/zip")
        } else {
            setResult(
                RESULT_OK,
                Intent().setDataAndType(uri, "application/zip")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            finish()
        }
    }

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Archive", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Share archive"))
            finish()
        } catch (_: RuntimeException) {
            busy = false
            setControlsBusy(false)
            toast("No app is available to share the archive.")
        }
    }

    private fun cancel() {
        archiveJob?.cancel()
        if (internalUpload) UploadStaging.discard(this, tabId, requestId)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun handleBack() {
        cancel()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("API 26-32 back compatibility")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack() else super.onBackPressed()
    }

    private fun persistOptions() {
        val c = ArchiveCompression.values().getOrElse(compression.selectedItemPosition) {
            ArchiveCompression.BALANCED
        }
        prefs.edit()
            .putString("compression", c.name)
            .putBoolean("paths", preserve.isChecked)
            .putBoolean("single", archiveSingle.isChecked)
            .putBoolean("saveCopy", saveCopy.isChecked)
            .apply()
    }

    private fun setControlsBusy(value: Boolean) {
        busy = value
        filename.isEnabled = !value
        compression.isEnabled = !value
        preserve.isEnabled = !value
        archiveSingle.isEnabled = !value
        saveCopy.isEnabled = !value
        action.text = if (value) "Compressing…" else actionLabel()
        action.isEnabled = !value && selected.isNotEmpty()
        action.alpha = if (action.isEnabled) 1f else .45f
    }

    private fun button(text: String, description: String, click: () -> Unit) =
        Ui.text(this, text, 13f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = Ui.ripple(this@ArchivePickerActivity, Ui.SURFACE_HIGH, 14f)
            setOnClickListener { click() }
        }

    private fun d(n: Int) = Ui.dp(this, n.toFloat())
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        do {
            value /= 1024.0
            i++
        } while (value >= 1024.0 && i < units.lastIndex)
        return DecimalFormat(if (value >= 100) "0" else "0.0").format(value) + " " + units[i]
    }

    companion object {
        const val EXTRA_TAB_ID = "bubble.archive.tab"
        const val EXTRA_REQUEST_ID = "bubble.archive.request"
        const val RESULT_LOCAL_PATH = "bubble.archive.local.path"

        private const val PICK_DOCUMENTS = 771
        private const val STATE_PICKER_LAUNCHED = "pickerLaunched"
        private const val STATE_URIS = "selectedUris"
        private const val STATE_FILENAME = "filename"
        private const val STATE_COMPRESSION = "compression"
        private const val STATE_PATHS = "paths"
        private const val STATE_SINGLE = "single"
        private const val STATE_SAVE_COPY = "saveCopy"
    }
}
