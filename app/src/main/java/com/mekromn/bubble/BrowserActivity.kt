package com.mekromn.bubble

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.util.Rational
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Fullscreen browsing retains one 56dp bar. File-selection Activities are not Home actions. */
class BrowserActivity : Activity() {
    lateinit var geckoView: GeckoView
        private set
    internal lateinit var workspace: Workspace
    val selectedSession: GeckoSession? get() = if (::workspace.isInitialized) workspace.selected?.session else null
    val pageTitle: String get() = if (::workspace.isInitialized) workspace.selected?.title.orEmpty() else ""
    val painted: Boolean get() = if (::workspace.isInitialized) workspace.selected?.painted == true else false
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var address: EditText
    private lateinit var error: TextView
    private lateinit var tabs: GlyphView
    private lateinit var back: GlyphView
    private lateinit var menuButton: GlyphView
    private var tray: TabTray? = null
    private var pendingIntent: Intent? = null
    private var started = false
    private var handoff = false
    private var collapsePending = false
    private var awaitingOverlay = false
    private var pendingMode = FloatingMode.BUBBLE
    private var notificationAsked = false
    private var pendingHide = false
    private var externalFlow = false
    private var enteringPip = false
    private var currentUrl = ""
    private var notice: String? = null
    private val meter = FrameMeter()
    private var measuring = false
    private val changed: () -> Unit = { render() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        buildUi()
        val url = if (state == null) incomingUrl(intent) else null
        workspace = Workspace.get(this, url); AccessPreferences.get(this)
        if (url != null && workspace.ready || intent.hasExtra(EXTRA_TAB) || intent.hasExtra(EXTRA_PIP)) pendingIntent = intent
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
    }
    override fun onStart() {
        super.onStart(); started = true; handoff = false
        BubbleService.active?.releaseForActivity(); stopService(Intent(this, BubbleService::class.java))
        workspace.host = WeakReference(this); workspace.visible = true
        workspace.listen(changed); workspace.applyPolicy(); Refresh.request(this)
        if (measuring) meter.start(this)
    }
    override fun onResume() {
        super.onResume(); externalFlow = false; enteringPip = false
        if (awaitingOverlay) {
            awaitingOverlay = false
            if (Settings.canDrawOverlays(this)) collapse(pendingMode) else toast("Floating mode needs display-over-other-apps permission.")
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (started && workspace.ready && !FileUi.busy && !externalFlow && !handoff && !enteringPip && !isInPictureInPictureMode && Settings.canDrawOverlays(this)) collapse(FloatingMode.BUBBLE, alreadyLeaving = true)
    }
    override fun onStop() {
        started = false; QuickPanel.dismissFor(root); workspace.unlisten(changed)
        if (workspace.host.get() === this) { workspace.visible = false; workspace.covered = false; workspace.detachSurface(geckoView); workspace.flush() }
        meter.stop(); super.onStop()
    }
    override fun onDestroy() {
        QuickPanel.dismissFor(root)
        if (::workspace.isInitialized) { workspace.detachSurface(geckoView); if (workspace.host.get() === this) workspace.host.clear() }
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingIntent = intent; render() }
    @Deprecated("API 26-32 back compatibility") override fun onBackPressed() = handleBack()
    private fun handleBack() {
        when {
            workspace.quickMenuVisible -> QuickPanel.dismissFor(root)
            tray?.visibility == View.VISIBLE -> showTabs(false)
            address.hasFocus() -> { address.clearFocus(); hideKeyboard() }
            !Settings.canDrawOverlays(this) -> AlertDialog.Builder(this).setTitle("Minimize to a bubble?").setMessage("Enable floating mode to keep your chats available after Home or Back.")
                .setPositiveButton("Enable") { _, _ -> collapse(FloatingMode.BUBBLE) }.setNegativeButton("Leave without bubble") { _, _ -> externalFlow = true; moveTaskToBack(true) }.show()
            else -> collapse(FloatingMode.BUBBLE)
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.isCtrlPressed && ::workspace.isInitialized && workspace.ready && !isInPictureInPictureMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_T -> { if (event.isShiftPressed) workspace.reopen() else workspace.create(); return true }
                KeyEvent.KEYCODE_TAB -> { workspace.cycle(event.isShiftPressed); return true }
                KeyEvent.KEYCODE_F -> { QuickMenus.find(menuButton, workspace); return true }
                KeyEvent.KEYCODE_L -> { address.requestFocus(); address.selectAll(); getSystemService(InputMethodManager::class.java).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT); return true }
                KeyEvent.KEYCODE_W -> { QuickMenus.close(menuButton, workspace, workspace.selectedId); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    internal fun detachSession(session: GeckoSession?) { if (geckoView.session === session && session != null) workspace.detachSurface(geckoView) }
    private fun render() {
        if (!started || handoff || !workspace.ready) return
        pendingIntent?.let { incoming ->
            pendingIntent = null
            incoming.getStringExtra(EXTRA_TAB)?.let(workspace::select) ?: incomingUrl(incoming)?.let { workspace.create(it) }
            if (incoming.getBooleanExtra(EXTRA_TRAY, false)) showTabs(true)
            if (incoming.getBooleanExtra(EXTRA_PIP, false)) root.post { enterNativePip() }
        }
        val tab = workspace.selected ?: return
        val session = tab.session
        if (session != null && session.isOpen) workspace.attachSurface(geckoView, session) else if (geckoView.session != null) workspace.detachSurface(geckoView)
        currentUrl = tab.url
        if (!address.hasFocus()) {
            val label = (if (tab.url.startsWith("http://")) "Not secure · " else "") + Policy.host(tab.url)
            val display = if (tab.profileId == ProfilePolicy.DEFAULT_ID) label else "${workspace.profileName(tab.profileId)} · $label"
            if (address.text.toString() != display) address.setText(display)
        }
        val backAlpha = if (tab.back) 1f else .55f; if (back.alpha != backAlpha) back.alpha = backAlpha
        tabs.count = workspace.tabs.size; tabs.contentDescription = "Workspace tabs, ${workspace.tabs.size} open"
        progress.visibility = if (tab.loading && !isInPictureInPictureMode) View.VISIBLE else View.INVISIBLE; progress.progress = tab.progress
        error.visibility = if (tab.error != null && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        val message = tab.error?.plus("\n\nTap to retry").orEmpty(); if (error.text.toString() != message) error.text = message
        if (tab.unread && workspace.chatVisible) { tab.unread = false; Replies.clear(this, tab.id); workspace.changed(true) }
        tray?.takeIf { it.visibility == View.VISIBLE }?.refresh(workspace)
        workspace.notice?.let { if (notice != it) { notice = it; toast(it) } }
    }
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG); isFocusableInTouchMode = true }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val content = FrameLayout(this); geckoView = LiveGeckoView(this)
        content.addView(geckoView, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(Ui.BLUE); progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE); visibility = View.INVISIBLE
        }
        content.addView(progress, FrameLayout.LayoutParams(-1, d(2), Gravity.TOP))
        error = Ui.text(this, "", 14f).apply {
            gravity = Gravity.CENTER; setPadding(d(20), d(22), d(20), d(22)); background = Ui.shape(this@BrowserActivity, Ui.SURFACE, 24f, Ui.LINE)
            visibility = View.GONE; setOnClickListener { workspace.retry() }
        }
        content.addView(error, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(d(22), 0, d(22), 0) })
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(4), d(4), d(4)); setBackgroundColor(Ui.SURFACE) }
        back = control("back", "Back in webpage") { if (workspace.selected?.back == true) selectedSession?.goBack() }.apply {
            tooltipText = "Back · hold for Forward, Stop and Refresh"
            setOnLongClickListener { if (::workspace.isInitialized && workspace.ready) QuickMenus.navigation(this, workspace); true }
        }
        bar.addView(back, LinearLayout.LayoutParams(d(48), d(48)))
        address = EditText(this).apply {
            setSingleLine(true); textSize = 13f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            hint = "Search or address"; contentDescription = "Address and search"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; imeOptions = EditorInfo.IME_ACTION_GO
            background = Ui.shape(this@BrowserActivity, Ui.BG, 16f); setPadding(d(12), 0, d(8), 0); setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, focused -> if (focused) { setText(currentUrl); selectAll() } else if (::workspace.isInitialized) render() }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { val url = text.toString(); clearFocus(); root.requestFocus(); hideKeyboard(); workspace.navigate(url); true } else false
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, d(44), 1f))
        tabs = control("tabs", "Workspace tabs") { showTabs(true) }.apply {
            tooltipText = "Workspace · hold for quick tabs"
            setOnLongClickListener { if (::workspace.isInitialized && workspace.ready) QuickMenus.tabs(this, workspace); true }
        }
        bar.addView(tabs, LinearLayout.LayoutParams(d(48), d(48)))
        bar.addView(control("float", "Open interactive floating chat", true) { collapse(FloatingMode.CHAT) }.apply {
            tooltipText = "Floating chat · hold to hide in notification"; setOnLongClickListener { hideToNotification(); true }
        }, LinearLayout.LayoutParams(d(48), d(48)))
        menuButton = control("menu", "Browser menu") { menu() }.apply {
            tooltipText = "Menu · hold for chat tools"
            setOnLongClickListener { if (::workspace.isInitialized && workspace.ready) QuickMenus.tools(this, workspace); true }
        }
        bar.addView(menuButton, LinearLayout.LayoutParams(d(48), d(48))); column.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root); root.requestFocus()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (isInPictureInPictureMode) root.setPadding(0, 0, 0, 0) else root.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    private fun control(glyph: String, label: String, accent: Boolean = false, action: () -> Unit) = GlyphView(this, glyph, label, accent).apply { setOnClickListener { if (::workspace.isInitialized && workspace.ready) action() } }
    internal fun showTabs(show: Boolean) {
        QuickPanel.dismissFor(root); hideKeyboard(); address.clearFocus(); root.requestFocus(); workspace.covered = show
        if (show && tray == null) {
            tray = TabTray(this, { workspace.select(it); showTabs(false) }, workspace::close, { workspace.create(); showTabs(false) }, { showTabs(false) })
            root.addView(tray, FrameLayout.LayoutParams(-1, -1))
        }
        tray?.let { if (show) it.refresh(workspace); Ui.show(it, show) }
    }
    private fun menu() {
        ControlsSheet.show(this, "Browser controls", listOf(
            "Downloads" to { BrowserDownloads.show(this) },
            "Profiles / accounts · ${workspace.profileName()}" to { ProfileMenus.show(tabs, workspace) },
            "Chat tools · prompts, notes and tabs" to { QuickMenus.tools(menuButton, workspace) },
            "New ChatGPT chat" to { workspace.create(); Unit },
            "Reopen last closed tab" to { if (workspace.reopen() == null) toast("No recently closed tabs"); Unit },
            "Find in conversation / page" to { QuickMenus.find(menuButton, workspace) },
            "Forward in webpage" to { selectedSession?.goForward(); Unit },
            (if (workspace.selected?.loading == true) "Stop loading" else "Reload page") to { if (workspace.selected?.loading == true) workspace.stopLoading() else workspace.retry() },
            "Floating chat · interactive" to { collapse(FloatingMode.CHAT) },
            "Hide to notification" to { hideToNotification() },
            "Bubble / edge access" to { AccessMenu.show(menuButton, workspace) },
            "Minimize to bubble" to { collapse(FloatingMode.BUBBLE) },
            "Android picture-in-picture · view only" to { enterNativePip(); Unit },
            (if (workspace.selected?.desktop == true) "Use mobile site" else "Use desktop site") to { workspace.desktop() },
            "Copy address" to { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Page address", currentUrl)) },
            "Share page" to { externalFlow = true; runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, currentUrl) }, "Share page")) }; Unit },
            "Local frame measurements" to { showMetrics() }
        ))
    }
    private fun showMetrics() {
        if (!measuring) { measuring = true; meter.start(this); toast("Measuring native window frames locally. Open the tab chooser, then return here.") }
        else AlertDialog.Builder(this).setTitle("Native frame timing").setMessage(meter.report(this)).setPositiveButton("Done", null).setNeutralButton("Stop") { _, _ -> measuring = false; meter.stop() }.show()
    }
    internal fun collapse(mode: FloatingMode = FloatingMode.BUBBLE, alreadyLeaving: Boolean = false) {
        if (collapsePending || !started || !workspace.ready) return
        QuickPanel.dismissFor(root); pendingMode = mode
        if (!Settings.canDrawOverlays(this)) {
            if (alreadyLeaving) return
            AlertDialog.Builder(this).setTitle("Enable floating workspace").setMessage("A single bubble opens your tab chooser and an interactive, resizable chat window. Home and Back minimize here too.")
                .setNegativeButton("Not now", null).setPositiveButton("Enable") { _, _ ->
                    awaitingOverlay = true; externalFlow = true
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: RuntimeException) { awaitingOverlay = false; externalFlow = false; toast("Could not open overlay settings.") }
                }.show(); return
        }
        if (!alreadyLeaving && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !notificationAsked) {
            notificationAsked = true; externalFlow = true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS); return
        }
        collapsePending = true; handoff = true; workspace.flush(); val wasPip = isInPictureInPictureMode
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                collapsePending = false
                if (code != 1) { handoff = false; if (started) { render(); toast("Floating window could not be attached. Your chats are retained.") }; return }
                if (!alreadyLeaving && !isFinishing) { if (wasPip) finish() else moveTaskToBack(true) }
            }
        }
        try { startForegroundService(Intent(this, BubbleService::class.java).putExtra(BubbleService.READY, reply).putExtra(BubbleService.MODE, mode.name)) }
        catch (_: RuntimeException) { collapsePending = false; handoff = false; if (!alreadyLeaving) toast("Android blocked the floating service.") }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, result: IntArray) {
        super.onRequestPermissionsResult(code, permissions, result)
        if (code == NOTIFICATIONS) { externalFlow = false; if (pendingHide) { pendingHide = false; hideToNotification() } else collapse(pendingMode) }
    }
    internal fun hideToNotification() {
        if (!started || !workspace.ready || collapsePending) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!notificationAsked) { notificationAsked = true; pendingHide = true; externalFlow = true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS) }
            else toast("Enable Bubble notifications before hiding. Your workspace stays open.")
            return
        }
        QuickPanel.dismissFor(root); hideKeyboard(); workspace.flush(); collapsePending = true; handoff = true
        val wasPip = isInPictureInPictureMode
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                collapsePending = false
                if (code == 1 && !isFinishing) { if (wasPip) finish() else moveTaskToBack(true) } else { handoff = false; if (started) render() }
            }
        }
        try { startForegroundService(Intent(this, BubbleService::class.java).setAction(BubbleService.HIDE).putExtra(BubbleService.READY, reply)) }
        catch (_: RuntimeException) { collapsePending = false; handoff = false; toast("Android blocked hiding. Your workspace stays open.") }
    }
    internal fun enterNativePip(): Boolean {
        if (!started || isInPictureInPictureMode || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        showTabs(false); hideKeyboard(); val bounds = Rect(); geckoView.getGlobalVisibleRect(bounds)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).setSourceRectHint(bounds).setActions(listOf(
            pipAction("Previous tab", PipTabReceiver.PREVIOUS, R.drawable.ic_previous), pipAction("Next tab", PipTabReceiver.NEXT, R.drawable.ic_next), pipAction("Minimize to bubble", PipTabReceiver.BUBBLE, R.drawable.ic_notification)))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(false).setSeamlessResizeEnabled(false)
        enteringPip = true
        val entered = runCatching { enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
        if (!entered) { enteringPip = false; bar.visibility = View.VISIBLE; toast("Android picture-in-picture is unavailable.") }; return entered
    }
    private fun pipAction(title: String, action: String, icon: Int): RemoteAction = RemoteAction(Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(this, 0, Intent(this, PipTabReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    override fun onPictureInPictureModeChanged(pip: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(pip, config); enteringPip = false; bar.visibility = if (pip) View.GONE else View.VISIBLE
        if (pip) { error.visibility = View.GONE; progress.visibility = View.INVISIBLE }; ViewCompat.requestApplyInsets(root); workspace.applyPolicy()
    }
    override fun onPictureInPictureUiStateChanged(state: PictureInPictureUiState) {
        if (Build.VERSION.SDK_INT >= 35) { super.onPictureInPictureUiStateChanged(state); if (state.isTransitioningToPip) bar.visibility = View.GONE }
    }
    internal fun chooseFile(prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val session = selectedSession ?: return GeckoResult.fromValue(prompt.dismiss())
        externalFlow = true; return FloatingFileActivity.launch(this, workspace.selectedId, session, prompt)
    }
    internal fun offerExternal(raw: String) {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        if (uri.scheme !in setOf("mailto", "tel", "sms", "geo")) { toast("Unsupported external link type."); return }
        AlertDialog.Builder(this).setTitle("Open another app?").setMessage("This page requested a ${uri.scheme} link.").setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ ->
            externalFlow = true
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: RuntimeException) { externalFlow = false; toast("No app could open that link.") }
        }.show()
    }
    private fun incomingUrl(incoming: Intent?): String? = when (incoming?.action) { Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(Policy::isWeb); else -> incoming?.dataString?.takeIf(Policy::isWeb) }
    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0) }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun d(n: Int) = Ui.dp(this, n.toFloat())
    companion object {
        const val EXTRA_TAB = "bubble.tab.id"
        const val EXTRA_TRAY = "bubble.workspace.tray"
        const val EXTRA_PIP = "bubble.native.pip"
        private const val NOTIFICATIONS = 411
    }
}
