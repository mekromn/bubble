package com.mekromn.bubble

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.util.Locale

/**
 * Bubble-owned attachment picker. Local shared storage is browsed natively; cloud/document providers
 * remain available through an explicit Providers button. Multiple selections are streamed directly
 * into one ZIP, so ChatGPT receives one attachment instead of N independent files.
 */
class ArchivePickerActivity : Activity() {
    private enum class SortMode { NAME, MODIFIED, SIZE }
    private data class Entry(val file: File)

    private val selectedLocal = LinkedHashSet<String>()
    private val selectedProvider = LinkedHashSet<Uri>()
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var sortMode = SortMode.NAME
    private var grid = false
    private var busy = false
    private var archiveJob: ArchiveJob? = null
    private lateinit var list: RecyclerView
    private lateinit var adapter: Entries
    private lateinit var breadcrumb: TextView
    private lateinit var accessState: TextView
    private lateinit var selectedState: TextView
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var filename: EditText
    private lateinit var compression: Spinner
    private lateinit var preserve: CheckBox
    private lateinit var archiveSingle: CheckBox
    private lateinit var saveCopy: CheckBox
    private lateinit var action: TextView
    private lateinit var gridButton: TextView
    private lateinit var sortButton: TextView
    private val tabId get() = intent.getStringExtra(EXTRA_TAB_ID).orEmpty()
    private val requestId get() = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
    private val internalUpload get() = tabId.isNotBlank() && requestId.isNotBlank()
    private val shareMode get() = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
    private val prefs by lazy { getSharedPreferences("archive-picker-v1", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        ArchiveCache.cleanup(this)
        sortMode = runCatching { SortMode.valueOf(prefs.getString("sort", SortMode.MODIFIED.name)!!) }.getOrDefault(SortMode.MODIFIED)
        grid = prefs.getBoolean("grid", false)
        collectIncomingShare()
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        refreshAccess()
        refreshFiles()
        updateSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::accessState.isInitialized) { refreshAccess(); refreshFiles() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(d(8), d(8), d(8), d(8))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹", "Up") { goUp() }, LinearLayout.LayoutParams(d(48), d(48)))
        breadcrumb = Ui.text(this, "Files", 15f, Ui.TEXT, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.START; setPadding(d(8), 0, d(8), 0)
        }
        top.addView(breadcrumb, LinearLayout.LayoutParams(0, d(52), 1f))
        sortButton = button("Sort", "Sort") { cycleSort() }
        gridButton = button("Grid", "Grid or list") { toggleGrid() }
        // "Modified" needs materially more room than the old 64dp slot on phone portrait.
        top.addView(sortButton, LinearLayout.LayoutParams(d(88), d(48)))
        top.addView(gridButton, LinearLayout.LayoutParams(d(68), d(48)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        accessState = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(d(10), d(7), d(10), d(7)) }
        root.addView(accessState, LinearLayout.LayoutParams(-1, -2))
        val accessActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        accessActions.addView(button("Local access", "Grant local file access") { requestAllFilesAccess() }, LinearLayout.LayoutParams(0, d(44), 1f))
        accessActions.addView(button("Providers…", "Browse Android document providers") { browseProviders() }, LinearLayout.LayoutParams(0, d(44), 1f))
        root.addView(accessActions, LinearLayout.LayoutParams(-1, -2))

        list = RecyclerView(this).apply { clipToPadding = false; setPadding(0, d(6), 0, d(6)) }
        adapter = Entries(); list.adapter = adapter; applyLayoutManager()
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        selectedState = Ui.text(this, "No files selected", 12f, Ui.TEXT, true).apply { setPadding(d(10), d(7), d(10), d(5)) }
        root.addView(selectedState, LinearLayout.LayoutParams(-1, -2))

        filename = EditText(this).apply {
            setSingleLine(true); setText(ArchiveEngine.defaultName()); textSize = 14f; setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED); hint = "Archive filename"; background = Ui.shape(this@ArchivePickerActivity, Ui.SURFACE_HIGH, 14f)
            setPadding(d(12), 0, d(12), 0)
        }
        root.addView(filename, LinearLayout.LayoutParams(-1, d(46)).apply { setMargins(0, d(3), 0, d(5)) })

        val options = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        compression = Spinner(this).apply {
            adapter = ArrayAdapter(this@ArchivePickerActivity, android.R.layout.simple_spinner_dropdown_item, ArchiveCompression.values().map { it.label })
            setSelection(ArchiveCompression.values().indexOfFirst { it.name == prefs.getString("compression", ArchiveCompression.BALANCED.name) }.coerceAtLeast(0))
        }
        options.addView(compression, LinearLayout.LayoutParams(0, d(48), 1f))
        preserve = CheckBox(this).apply { text = "Paths"; setTextColor(Ui.TEXT); isChecked = prefs.getBoolean("paths", false) }
        archiveSingle = CheckBox(this).apply { text = "Archive 1"; setTextColor(Ui.TEXT); isChecked = prefs.getBoolean("single", true) }
        options.addView(preserve, LinearLayout.LayoutParams(-2, d(48)))
        options.addView(archiveSingle, LinearLayout.LayoutParams(-2, d(48)))
        root.addView(options, LinearLayout.LayoutParams(-1, -2))
        saveCopy = CheckBox(this).apply {
            text = "Save a copy to Downloads/Bubble"; setTextColor(Ui.TEXT); isChecked = prefs.getBoolean("saveCopy", false)
        }
        root.addView(saveCopy, LinearLayout.LayoutParams(-1, d(42)))

        val requested = intent.type.orEmpty()
        if (!internalUpload && requested.isNotBlank() && requested != "*/*" && requested != "application/zip" && !shareMode) {
            root.addView(Ui.text(this, "The requesting app asked for $requested. Returning a ZIP may be rejected by that app.", 11f, 0xffffc66d.toInt()).apply {
                setPadding(d(10), d(5), d(10), d(5))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        progressText = Ui.text(this, "", 11f, Ui.MUTED).apply { visibility = View.GONE; setPadding(d(10), d(5), d(10), d(3)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        root.addView(progressText, LinearLayout.LayoutParams(-1, -2)); root.addView(progress, LinearLayout.LayoutParams(-1, d(4)))

        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6), 0, 0) }
        bottom.addView(button("Cancel", "Cancel") { cancel() }, LinearLayout.LayoutParams(d(92), d(50)))
        action = button(actionLabel(), actionLabel()) { compressOrReturn() }
        bottom.addView(action, LinearLayout.LayoutParams(0, d(50), 1f).apply { marginStart = d(6) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                d(8) + safe.left,
                d(8) + safe.top,
                d(8) + safe.right,
                d(8) + maxOf(safe.bottom, keyboard.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun actionLabel() = when {
        internalUpload -> "Compress & Attach"
        shareMode -> "Compress & Share"
        else -> "Compress & Send"
    }

    private fun refreshAccess() {
        accessState.text = when {
            Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() -> "Local shared storage · full native browsing enabled"
            Build.VERSION.SDK_INT >= 30 -> "Local shared storage needs All files access. Providers still work without it."
            else -> "Local shared storage"
        }
    }

    private fun hasLocalAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: RuntimeException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun browseProviders() {
        if (busy) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(pick, PICK_PROVIDER) }
        catch (_: RuntimeException) { toast("Android could not open document providers.") }
    }

    @Deprecated("Provider picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PROVIDER || resultCode != RESULT_OK) return
        data?.data?.let { selectedProvider += it }
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) selectedProvider += clip.getItemAt(i).uri }
        updateSelection()
    }

    private fun collectIncomingShare() {
        if (!shareMode) return
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { selectedProvider += it }
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { selectedProvider += it }
    }

    private fun refreshFiles() {
        breadcrumb.text = currentDir.absolutePath
        sortButton.text = when (sortMode) { SortMode.NAME -> "Name"; SortMode.MODIFIED -> "Modified"; SortMode.SIZE -> "Size" }
        gridButton.text = if (grid) "List" else "Grid"
        if (!hasLocalAccess() || !currentDir.exists() || !currentDir.isDirectory) { adapter.submit(emptyList()); return }
        val entries = currentDir.listFiles()?.filter { it.isDirectory || it.isFile } ?: emptyList()
        val comparator = Comparator<File> { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            when (sortMode) {
                SortMode.NAME -> a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
                SortMode.MODIFIED -> b.lastModified().compareTo(a.lastModified())
                SortMode.SIZE -> b.length().compareTo(a.length())
            }
        }
        adapter.submit(entries.sortedWith(comparator).map(::Entry))
    }

    private fun goUp() {
        if (busy) return
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val here = runCatching { currentDir.canonicalFile }.getOrDefault(currentDir)
        if (here == root) { finish(); return }
        currentDir.parentFile?.let { currentDir = it; refreshFiles() }
    }

    private fun cycleSort() {
        if (busy) return
        sortMode = when (sortMode) { SortMode.NAME -> SortMode.MODIFIED; SortMode.MODIFIED -> SortMode.SIZE; SortMode.SIZE -> SortMode.NAME }
        prefs.edit().putString("sort", sortMode.name).apply(); refreshFiles()
    }

    private fun toggleGrid() {
        if (busy) return
        grid = !grid; prefs.edit().putBoolean("grid", grid).apply(); applyLayoutManager(); refreshFiles()
    }

    private fun applyLayoutManager() { list.layoutManager = if (grid) GridLayoutManager(this, 3) else LinearLayoutManager(this) }

    private fun toggle(file: File) {
        val path = file.absolutePath
        if (!selectedLocal.add(path)) selectedLocal.remove(path)
        adapter.notifyDataSetChanged(); updateSelection()
    }

    private fun updateSelection() {
        if (!::selectedState.isInitialized) return
        val localFiles = selectedLocal.map(::File)
        val knownBytes = localFiles.sumOf { it.length().coerceAtLeast(0L) }
        val count = selectedLocal.size + selectedProvider.size
        selectedState.text = if (count == 0) "No files selected" else "$count selected · ${human(knownBytes)}${if (selectedProvider.isNotEmpty()) " + provider files" else ""}"
        action.isEnabled = count > 0 && !busy; action.alpha = if (action.isEnabled) 1f else .45f
    }

    private fun compressOrReturn() {
        if (busy) return
        val locals = selectedLocal.map(::File).filter { it.isFile && it.canRead() }
        val sources = ArrayList<ArchiveSource>()
        val storageRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        locals.forEach { file ->
            val relative = if (preserve.isChecked && storageRoot != null) runCatching {
                file.parentFile?.canonicalFile?.relativeTo(storageRoot)?.path
            }.getOrNull() else null
            sources += ArchiveSource(Uri.fromFile(file), file.name, file.length(), relative)
        }
        selectedProvider.forEach { sources += ArchiveEngine.source(this, it) }
        if (sources.isEmpty()) { toast("No readable files selected."); return }
        persistOptions()
        if (sources.size == 1 && !archiveSingle.isChecked) { returnSingle(sources.first()); return }

        busy = true; updateSelection(); setControlsBusy(true)
        val job = ArchiveJob(); archiveJob = job
        val selectedCompression = ArchiveCompression.values().getOrElse(compression.selectedItemPosition) { ArchiveCompression.BALANCED }
        val preservePaths = preserve.isChecked
        val keepCopy = saveCopy.isChecked
        val out = if (internalUpload) UploadStaging.archiveOutput(this, tabId, requestId, filename.text.toString())
            else ArchiveCache.output(this, filename.text.toString())
        UploadStaging.io.execute {
            try {
                val archive = ArchiveEngine.createZip(this, sources, out, selectedCompression, preservePaths, job) { p ->
                    runOnUiThread { showProgress(p) }
                }
                val copyFailure = if (keepCopy) runCatching { savePublicCopy(archive) }.exceptionOrNull() else null
                runOnUiThread {
                    if (copyFailure != null) toast("Archive created, but the Downloads copy could not be saved.")
                    completeArchive(archive)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    busy = false; archiveJob = null; setControlsBusy(false); updateSelection()
                    if (!job.cancelled.get()) toast("Could not create the archive. Check free space and selected file access.")
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
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create Downloads item")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().buffered().use { it.copyTo(output, 128 * 1024) } }
                    ?: throw IOException("Could not open Downloads output")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
            } catch (t: Throwable) { contentResolver.delete(uri, null, null); throw t }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bubble").apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    private fun returnSingle(source: ArchiveSource) {
        persistOptions(); busy = true; setControlsBusy(true)
        if (internalUpload && source.uri.scheme == "file") {
            setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, source.uri.path)); finish(); return
        }
        if (internalUpload && source.uri.scheme == "content") {
            UploadStaging.io.execute {
                val staged = runCatching { UploadStaging.prepare(this, tabId, requestId, listOf(source.uri), UploadStaging.Job()) }.getOrElse { emptyList() }
                runOnUiThread {
                    val path = staged.firstOrNull()?.path
                    if (path == null) { busy = false; setControlsBusy(false); toast("Could not read that file.") }
                    else { setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, path)); finish() }
                }
            }
            return
        }
        val uri = when (source.uri.scheme) {
            "content" -> source.uri
            "file" -> runCatching { FileProvider.getUriForFile(this, "$packageName.archives", File(source.uri.path.orEmpty())) }.getOrNull()
            else -> null
        }
        if (uri == null) { busy = false; setControlsBusy(false); toast("Could not share that file."); return }
        if (shareMode) share(uri, contentResolver.getType(uri) ?: "application/octet-stream")
        else { setResult(RESULT_OK, Intent().setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); finish() }
    }

    private fun showProgress(p: ArchiveProgress) {
        progress.visibility = View.VISIBLE; progressText.visibility = View.VISIBLE
        progressText.text = "${p.fileIndex}/${p.fileCount} · ${p.currentName} · ${human(p.bytesProcessed)}"
        progress.progress = if (p.totalBytes > 0) ((p.bytesProcessed.toDouble() / p.totalBytes) * progress.max).toInt().coerceIn(0, progress.max) else 0
    }

    private fun completeArchive(file: File) {
        archiveJob = null; progress.progress = progress.max; progressText.text = "Done · ${human(file.length())}"
        if (internalUpload) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_LOCAL_PATH, file.absolutePath)); finish(); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.archives", file)
        if (shareMode) share(uri, "application/zip")
        else {
            setResult(RESULT_OK, Intent().setDataAndType(uri, "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish()
        }
    }

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newUri(contentResolver, "Archive", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "Share archive")); finish() }
        catch (_: RuntimeException) { busy = false; setControlsBusy(false); toast("No app is available to share the archive.") }
    }

    private fun cancel() {
        archiveJob?.cancel()
        if (internalUpload) UploadStaging.discard(this, tabId, requestId)
        setResult(RESULT_CANCELED); finish()
    }

    private fun handleBack() { if (busy) cancel() else goUp() }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("API 26-32 back compatibility")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack() else super.onBackPressed()
    }

    private fun persistOptions() {
        val c = ArchiveCompression.values().getOrElse(compression.selectedItemPosition) { ArchiveCompression.BALANCED }
        prefs.edit().putString("compression", c.name).putBoolean("paths", preserve.isChecked)
            .putBoolean("single", archiveSingle.isChecked).putBoolean("saveCopy", saveCopy.isChecked).apply()
    }

    private fun setControlsBusy(value: Boolean) {
        busy = value
        list.isEnabled = !value; filename.isEnabled = !value; compression.isEnabled = !value; preserve.isEnabled = !value
        archiveSingle.isEnabled = !value; saveCopy.isEnabled = !value; sortButton.isEnabled = !value; gridButton.isEnabled = !value
        action.text = if (value) "Compressing…" else actionLabel()
    }

    private fun button(text: String, description: String, click: () -> Unit) = Ui.text(this, text, 13f, Ui.TEXT, true).apply {
        gravity = Gravity.CENTER; contentDescription = description; isClickable = true; isFocusable = true
        background = Ui.ripple(this@ArchivePickerActivity, Ui.SURFACE_HIGH, 14f); setOnClickListener { click() }
    }

    private inner class Entries : RecyclerView.Adapter<EntryHolder>() {
        private var entries: List<Entry> = emptyList()
        fun submit(value: List<Entry>) { entries = value; notifyDataSetChanged() }
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val box = LinearLayout(parent.context).apply {
                orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL; setPadding(d(9), d(7), d(9), d(7)); minimumHeight = d(if (grid) 92 else 58)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, d(2), 0, d(2))
                }
            }
            val mark = TextView(parent.context).apply { textSize = 21f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
            val name = Ui.text(parent.context, "", if (grid) 11f else 13f, Ui.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val meta = Ui.text(parent.context, "", 10f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val text = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(meta) }
            box.addView(mark, LinearLayout.LayoutParams(d(42), d(42)))
            box.addView(text, LinearLayout.LayoutParams(if (grid) -1 else 0, -2, if (grid) 0f else 1f))
            return EntryHolder(box, mark, name, meta)
        }
        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val file = entries[position].file; val chosen = selectedLocal.contains(file.absolutePath)
            holder.mark.text = if (file.isDirectory) "▣" else if (chosen) "✓" else "□"
            holder.mark.setTextColor(if (chosen) Ui.ACTIVE else Ui.MUTED)
            holder.name.text = file.name.ifBlank { file.absolutePath }
            holder.meta.text = if (file.isDirectory) "Folder · ${date(file.lastModified())}" else "${human(file.length())} · ${date(file.lastModified())}"
            holder.box.background = Ui.ripple(this@ArchivePickerActivity, if (chosen) Ui.SURFACE_HIGH else Ui.SURFACE, 14f)
            holder.box.setOnClickListener { if (busy) return@setOnClickListener; if (file.isDirectory) { currentDir = file; refreshFiles() } else toggle(file) }
            holder.box.setOnLongClickListener { if (!file.isDirectory) { toggle(file); true } else false }
        }
    }

    private class EntryHolder(val box: LinearLayout, val mark: TextView, val name: TextView, val meta: TextView) : RecyclerView.ViewHolder(box)

    private fun d(n: Int) = Ui.dp(this, n.toFloat())
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun date(ms: Long) = if (ms <= 0) "" else android.text.format.DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), 60_000).toString()
    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble(); var i = -1
        do { value /= 1024.0; i++ } while (value >= 1024.0 && i < units.lastIndex)
        return DecimalFormat(if (value >= 100) "0" else "0.0").format(value) + " " + units[i]
    }

    companion object {
        const val EXTRA_TAB_ID = "bubble.archive.tab"
        const val EXTRA_REQUEST_ID = "bubble.archive.request"
        const val RESULT_LOCAL_PATH = "bubble.archive.local.path"
        private const val PICK_PROVIDER = 771
    }
}
