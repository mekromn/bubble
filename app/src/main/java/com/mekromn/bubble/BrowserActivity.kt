package com.mekromn.bubble

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** One native Activity view owns the compositor. No transparent touch-routing layers or Compose. */
class BrowserActivity : Activity() {
    lateinit var geckoView: GeckoView
        private set
    val selectedSession: GeckoSession? get() = if (::workspace.isInitialized) workspace.selected?.session else null
    val pageTitle: String get() = if (::workspace.isInitialized) workspace.selected?.title.orEmpty() else ""
    val painted: Boolean get() = if (::workspace.isInitialized) workspace.selected?.painted == true else false
    internal lateinit var workspace: Workspace
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var omnibox: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var tabsButton: GlyphView
    private lateinit var reloadButton: GlyphView
    private lateinit var backButton: GlyphView
    private lateinit var forwardButton: GlyphView
    private var tray: TabTray? = null
    private var displayedSession: GeckoSession? = null
    private var started = false
    private var pendingIntent: Intent? = null
    private var collapsePending = false
    private var awaitingOverlay = false
    private var shownNotice: String? = null
    private val frameMeter = FrameMeter()
    private var measuring = false
    private val fileIo = Executors.newSingleThreadExecutor { r -> Thread(r, "Bubble-file-selection").apply { isDaemon = true } }
    private data class FileRequest(val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>, val tabId: String, val session: GeckoSession?)
    private var fileRequest: FileRequest? = null
    private val onWorkspaceChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        buildChrome()
        val initial = if (savedInstanceState == null) incomingUrl(intent) else null
        workspace = Workspace.get(this, initial)
        if (initial != null && workspace.ready) pendingIntent = intent
        else if (intent.hasExtra(EXTRA_TAB) || intent.getBooleanExtra(EXTRA_TRAY, false)) pendingIntent = intent
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }
    override fun onStart() {
        super.onStart(); started = true
        workspace.host = WeakReference(this); workspace.visible = true
        // Explicit foreground ownership; no persisted flag is allowed to background this Activity.
        stopService(Intent(this, BubbleService::class.java))
        workspace.listen(onWorkspaceChanged)
        workspace.applyPolicy()
        Refresh.request(this)
        if (measuring) frameMeter.start(this)
    }
    override fun onResume() {
        super.onResume()
        if (awaitingOverlay) {
            awaitingOverlay = false
            if (Settings.canDrawOverlays(this)) collapse() else toast("Floating bubble permission was not granted.")
        }
    }
    override fun onStop() {
        started = false
        workspace.unlisten(onWorkspaceChanged)
        if (workspace.host.get() === this) {
            workspace.visible = false
            detachSession(displayedSession)
            workspace.applyPolicy(); workspace.flush()
        }
        frameMeter.stop()
        super.onStop()
    }
    override fun onDestroy() {
        fileRequest?.let { request -> if (!request.prompt.isComplete) request.result.complete(request.prompt.dismiss()) }
        fileRequest = null; fileIo.shutdown()
        if (workspace.host.get() === this) workspace.host.clear()
        detachSession(displayedSession)
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); pendingIntent = intent; render()
    }
    @Deprecated("Legacy back compatibility") override fun onBackPressed() { handleBack() }
    private fun handleBack() {
        when {
            tray?.visibility == View.VISIBLE -> showTabs(false)
            omnibox.hasFocus() -> { omnibox.clearFocus(); hideKeyboard() }
            workspace.selected?.back == true -> selectedSession?.goBack()
            else -> finish()
        }
    }
    internal fun detachSession(session: GeckoSession?) {
        if (session != null && displayedSession === session) {
            geckoView.releaseSession(); displayedSession = null
        }
    }
    private fun render() {
        if (!started || !workspace.ready) return
        pendingIntent?.let { incoming ->
            pendingIntent = null
            val tabId = incoming.getStringExtra(EXTRA_TAB)
            if (tabId != null) workspace.select(tabId)
            else incomingUrl(incoming)?.let { workspace.create(it) }
            if (incoming.getBooleanExtra(EXTRA_TRAY, false)) showTabs(true)
        }
        val tab = workspace.selected ?: return
        val session = tab.session
        if (session != null && session.isOpen && session !== displayedSession) {
            detachSession(displayedSession)
            geckoView.setSession(session); displayedSession = session
            workspace.applyPolicy()
            geckoView.post { if (started) Refresh.request(this) }
        }
        if (!omnibox.hasFocus() && omnibox.text.toString() != tab.url) omnibox.setText(tab.url)
        val message = when {
            tab.error != null -> "PAGE NEEDS ATTENTION"
            tab.generating -> "GENERATING"
            tab.loading -> "CONNECTING"
            else -> "${workspace.tabs.size} ${if (workspace.tabs.size == 1) "CHAT" else "TABS"} · WORKSPACE"
        }
        if (status.text != message) status.text = message
        status.setTextColor(if (tab.generating) Ui.MINT else Ui.MUTED)
        progress.visibility = if (tab.loading) View.VISIBLE else View.INVISIBLE
        progress.progress = tab.progress
        backButton.isEnabled = tab.back; backButton.invalidate()
        forwardButton.isEnabled = tab.forward; forwardButton.invalidate()
        val glyph = if (tab.loading) "close" else "reload"
        if (reloadButton.glyph != glyph) { reloadButton.glyph = glyph; reloadButton.invalidate() }
        reloadButton.contentDescription = if (tab.loading) "Stop loading" else "Reload page"
        tabsButton.count = workspace.tabs.size
        tabsButton.contentDescription = "Open workspace, ${workspace.tabs.size} tabs"
        errorCard.visibility = if (tab.error != null) View.VISIBLE else View.GONE
        errorText.text = tab.error.orEmpty()
        tray?.refresh(workspace)
        workspace.notice?.let { notice -> if (notice != shownNotice) { shownNotice = notice; toast(notice) } }
    }
    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1,-1))
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(16),d(3),d(10),d(3))
        }
        val brand = Ui.text(this, "bubble", 22f, Ui.TEXT, true)
        val brandStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        brandStack.addView(brand)
        status = Ui.text(this, "STARTING WORKSPACE", 10f, Ui.MUTED).apply { letterSpacing = 0.1f; setPadding(0,d(4),0,0) }
        brandStack.addView(status)
        header.addView(brandStack, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("menu", "Browser menu") { menu() }, LinearLayout.LayoutParams(d(48),d(48)))
        column.addView(header, LinearLayout.LayoutParams(-1,d(60)))
        pageHost = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        geckoView = GeckoView(this)
        // Gecko handles its own SurfaceView lifecycle; we neither transform nor recreate it for UI animation.
        pageHost.addView(geckoView, FrameLayout.LayoutParams(-1,-1))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(Ui.BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE); visibility = View.INVISIBLE
        }
        pageHost.addView(progress, FrameLayout.LayoutParams(-1,d(3),Gravity.TOP))
        errorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(22),d(22),d(22),d(16))
            background = Ui.shape(this@BrowserActivity, Ui.SURFACE, 24f, Ui.LINE); visibility = View.GONE
        }
        errorCard.addView(Ui.text(this, "Let's reconnect", 21f, Ui.TEXT, true))
        errorText = Ui.text(this,"",14f,Ui.MUTED).apply { setPadding(0,d(12),0,d(16)) }
        errorCard.addView(errorText)
        val retry = Ui.text(this,"Retry page",15f,Ui.BLUE,true).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(this@BrowserActivity,Ui.SURFACE_HIGH,16f)
            setOnClickListener { workspace.retry() }
        }
        errorCard.addView(retry, LinearLayout.LayoutParams(-1,d(48)))
        pageHost.addView(errorCard, FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply { setMargins(d(24),0,d(24),0) })
        column.addView(pageHost, LinearLayout.LayoutParams(-1,0,1f))
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(8),d(4),d(8),d(4))
            background = Ui.shape(this@BrowserActivity, Ui.SURFACE, 26f, Ui.LINE)
        }
        val addressRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        omnibox = EditText(this).apply {
            setSingleLine(true); textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            hint = "Search or enter address"; contentDescription = "Address and search"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; background = null; setPadding(d(12),0,d(4),0)
            selectAllOnFocus = true
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    val address = text.toString(); clearFocus(); hideKeyboard(); workspace.navigate(address); true
                } else false
            }
        }
        addressRow.addView(omnibox, LinearLayout.LayoutParams(0,d(48),1f))
        reloadButton = button("reload", "Reload page") {
            if (workspace.selected?.loading == true) selectedSession?.stop() else workspace.retry()
        }
        addressRow.addView(reloadButton, LinearLayout.LayoutParams(d(48),d(48)))
        dock.addView(addressRow)
        dock.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(-1,d(1)))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0,d(3),0,0) }
        backButton = button("back","Back") { selectedSession?.goBack() }
        forwardButton = button("forward","Forward") { selectedSession?.goForward() }
        tabsButton = button("tabs","Workspace tabs") { showTabs(true) }
        val add = button("add","New ChatGPT chat",true) { workspace.create(); hideKeyboard(); omnibox.clearFocus() }
        val minimize = button("bubble","Collapse workspace to one bubble",true) { collapse() }
        listOf(backButton,forwardButton,add,tabsButton,minimize).forEach { controls.addView(it, LinearLayout.LayoutParams(0,d(48),1f)) }
        dock.addView(controls)
        column.addView(dock, LinearLayout.LayoutParams(-1,-2).apply { setMargins(d(10),d(6),d(10),d(8)) })
        column.isFocusableInTouchMode = true; column.requestFocus()
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(safe.left,safe.top,safe.right,maxOf(safe.bottom,keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    private fun button(glyph: String, label: String, accent: Boolean = false, action: () -> Unit) =
        GlyphView(this,glyph,label,accent).apply { setOnClickListener { if (::workspace.isInitialized && workspace.ready) action() } }
    internal fun showTabs(show: Boolean) {
        hideKeyboard(); omnibox.clearFocus()
        if (show && tray == null) {
            tray = TabTray(this, { id -> workspace.select(id); showTabs(false) },
                { id -> workspace.close(id) }, { workspace.create(); showTabs(false) }, { showTabs(false) })
            root.addView(tray, FrameLayout.LayoutParams(-1,-1))
        }
        tray?.let { if (show) it.refresh(workspace); Ui.show(it,show) }
    }
    private fun menu() {
        val options = arrayOf("New ChatGPT chat", "Go to Google", "${if(workspace.selected?.desktop == true) "Mobile" else "Desktop"} site", "Copy address", "Share page", "${if(measuring) "View" else "Start"} local frame measurement", "About this rebuild")
        AlertDialog.Builder(this).setTitle("Workspace controls").setItems(options) { _, index ->
            when(index) {
                0 -> workspace.create()
                1 -> workspace.create("https://www.google.com/")
                2 -> workspace.desktop()
                3 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Page address",workspace.selected?.url.orEmpty())); toast("Address copied") }
                4 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,workspace.selected?.url) },"Share page"))
                5 -> {
                    if (!measuring) { measuring=true; frameMeter.start(this); toast("Measuring locally. Open and close the workspace, then return here.") }
                    else AlertDialog.Builder(this).setTitle("Native frame timing").setMessage(frameMeter.report(this))
                        .setPositiveButton("Done",null).setNeutralButton("Stop") { _,_ -> frameMeter.stop(); measuring=false }.show()
                }
                6 -> AlertDialog.Builder(this).setTitle("Bubble ${BuildConfig.VERSION_NAME}")
                    .setMessage("Clean native rebuild · Gecko browser engine\n\nOne bubble, multiple live ChatGPT sessions. No old application runtime or Compose renderer host.\n\n120 Hz is requested on supported displays; zero jank is a measurement target, not a guarantee. Voice, downloads and site-specific flows require separate validation. Existing legacy workspace data is left untouched.")
                    .setPositiveButton("Done",null).show()
            }
        }.show()
    }
    private fun collapse() {
        if (collapsePending || !started) return
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle("Keep your chats in a bubble")
                .setMessage("Allow Bubble to display one small, draggable workspace button over other apps. Nothing outside that button is intercepted.")
                .setNegativeButton("Not now",null).setPositiveButton("Allow") { _,_ ->
                    awaitingOverlay=true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !notificationAsked) {
            notificationAsked=true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),NOTIFICATIONS)
            return
        }
        collapsePending = true
        workspace.flush()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                collapsePending=false
                if (!started || isFinishing) { if (code == 1) stopService(Intent(this@BrowserActivity,BubbleService::class.java)); return }
                if (code == 1) moveTaskToBack(true) else toast("The floating window could not be created. Your workspace stayed open.")
            }
        }
        try { startForegroundService(Intent(this,BubbleService::class.java).putExtra(BubbleService.READY,receiver)) }
        catch (_: RuntimeException) { collapsePending=false; toast("Android blocked the floating service. Your chats remain open.") }
    }
    private var notificationAsked=false
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if (code == NOTIFICATIONS) collapse()
    }
    internal fun offerExternal(raw: String) {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        if (uri.scheme !in setOf("mailto","tel","sms","geo")) { toast("This external link type is not supported."); return }
        AlertDialog.Builder(this).setTitle("Open another app?").setMessage("This page requested a ${uri.scheme} link.")
            .setNegativeButton("Cancel",null).setPositiveButton("Open") { _,_ ->
                try { startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                catch (_: RuntimeException) { toast("No app could open that link.") }
            }.show()
    }
    internal fun chooseFile(prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (fileRequest != null) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        fileRequest = FileRequest(prompt,result,workspace.selectedId,selectedSession)
        val types = prompt.mimeTypes.orEmpty().filter { it.contains('/') }.toTypedArray()
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = if(types.size==1)types[0] else "*/*"
            if (types.size>1) putExtra(Intent.EXTRA_MIME_TYPES,types)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,prompt.type==GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(pick,FILES) }
        catch (_: RuntimeException) { fileRequest=null; result.complete(prompt.dismiss()) }
        return result
    }
    @Deprecated("Native Activity result bridge") override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code,resultCode,data)
        if (code != FILES) return
        val request = fileRequest ?: return
        val uris = ArrayList<Uri>()
        if(resultCode==RESULT_OK) {
            data?.data?.let { uris += it }
            data?.clipData?.let { clip -> for(i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        }
        val multiple = request.prompt.type==GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
        val candidates = uris.distinct().let { if(multiple)it else it.take(1) }
        fileIo.execute {
            val valid = candidates.filter { uri ->
                uri.scheme=="content" && uri.authority!=packageName && runCatching {
                    contentResolver.openAssetFileDescriptor(uri,"r")?.use { true } ?: false
                }.getOrDefault(false)
            }
            runOnUiThread {
                if (fileRequest !== request) return@runOnUiThread
                fileRequest = null
                if (request.prompt.isComplete) return@runOnUiThread
                val stillSame = !isDestroyed && workspace.selectedId==request.tabId && selectedSession===request.session
                request.result.complete(if(stillSame && valid.isNotEmpty()) request.prompt.confirm(applicationContext,valid.toTypedArray()) else request.prompt.dismiss())
            }
        }
    }
    private fun incomingUrl(incoming: Intent?): String? = when(incoming?.action) {
        Intent.ACTION_VIEW -> incoming.dataString?.takeIf(Policy::isWeb)
        Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(Policy::isWeb)
        else -> incoming?.dataString?.takeIf(Policy::isWeb)
    }
    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(omnibox.windowToken,0) }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun d(n: Int)=Ui.dp(this,n.toFloat())
    companion object {
        const val EXTRA_TAB="bubble.tab.id"
        const val EXTRA_TRAY="bubble.workspace.tray"
        private const val FILES=410
        private const val NOTIFICATIONS=411
    }
}
