package com.mekromn.bubble

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.navigation.SharedUrlExtractor
import com.mekromn.bubble.browser.session.BrowserSessionState
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import com.mekromn.bubble.data.db.SavedSessionRestoreMode
import com.mekromn.bubble.data.db.SavedSessionSummary
import com.mekromn.bubble.heads.service.FloatingHeadService
import com.mekromn.bubble.ui.browser.BrowserViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) dispatchIncomingIntent(intent)
        setContent {
            BubbleTheme {
                val browserViewModel: BrowserViewModel = viewModel()
                val pageState by browserViewModel.pageState.collectAsState()
                PredictiveBackHandler(enabled = true) { progress ->
                    try {
                        progress.collect()
                        if (!pageState.canGoBack || !browserViewModel.goBack()) finish()
                    } catch (_: CancellationException) {
                        // Cancelled predictive-back gestures must not navigate or exit.
                    }
                }
                BrowserScreen(browserViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingIntent(intent)
    }

    private fun dispatchIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        lifecycleScope.launch {
            val app = application as BubbleApplication
            val sessions = app.runtime.sessions
            sessions.initialize()

            incoming.getStringExtra(EXTRA_RESTORE_TAB_ID)?.let { rawId ->
                runCatching { sessions.activate(TabId(rawId)) }
                return@launch
            }

            val url = when (incoming.action) {
                Intent.ACTION_VIEW -> incoming.dataString?.takeIf { value ->
                    val scheme = runCatching { value.toUri().scheme }.getOrNull()
                    scheme.equals("http", true) || scheme.equals("https", true)
                }
                Intent.ACTION_SEND -> SharedUrlExtractor.extract(
                    incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
                )
                else -> null
            }
            if (url != null) sessions.createTab(url)
        }
    }

    companion object {
        const val EXTRA_RESTORE_TAB_ID = "com.mekromn.bubble.extra.RESTORE_TAB_ID"
    }
}

@Composable
private fun BrowserScreen(viewModel: BrowserViewModel) {
    val session by viewModel.sessionState.collectAsState()
    val page by viewModel.pageState.collectAsState()
    val webView by viewModel.activeWebView.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState(initial = emptyList())
    val context = LocalContext.current

    var explainOverlay by remember { mutableStateOf(false) }
    var pendingOverlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSessionManager by remember { mutableStateOf(false) }
    var showSaveSession by remember { mutableStateOf(false) }
    var sessionName by remember { mutableStateOf("") }

    fun minimizeNow() {
        viewModel.minimizeActiveToHead {
            FloatingHeadService.start(context)
        }
    }

    fun runWithOverlayPermission(action: () -> Unit) {
        if (Settings.canDrawOverlays(context)) {
            action()
        } else {
            pendingOverlayAction = action
            explainOverlay = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingOverlayAction
        pendingOverlayAction = null
        if (Settings.canDrawOverlays(context)) action?.invoke()
    }

    if (explainOverlay) {
        AlertDialog(
            onDismissRequest = {
                explainOverlay = false
                pendingOverlayAction = null
            },
            title = { Text("Allow floating heads") },
            text = {
                Text(
                    "Bubble needs Android's display-over-other-apps permission only to show the draggable browser heads you create or restore. The rest of the browser works without it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        explainOverlay = false
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        explainOverlay = false
                        pendingOverlayAction = null
                    },
                ) { Text("Not now") }
            },
        )
    }

    if (showSessionManager) {
        SessionManagerDialog(
            sessions = savedSessions,
            onDismiss = { showSessionManager = false },
            onSave = {
                showSessionManager = false
                sessionName = ""
                showSaveSession = true
            },
            onRestore = { id, mode ->
                showSessionManager = false
                viewModel.restoreSavedSession(id, mode) { restored ->
                    if (restored && viewModel.sessionState.value.tabs.any {
                            it.presentationState == PresentationState.HEAD
                        }
                    ) {
                        runWithOverlayPermission { FloatingHeadService.start(context) }
                    }
                }
            },
            onDelete = viewModel::deleteSavedSession,
        )
    }

    if (showSaveSession) {
        AlertDialog(
            onDismissRequest = { showSaveSession = false },
            title = { Text("Save current session") },
            text = {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    singleLine = true,
                    label = { Text("Session name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = sessionName
                        showSaveSession = false
                        viewModel.saveCurrentSession(name)
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveSession = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BrowserToolbar(
                page = page,
                session = session,
                onNavigate = viewModel::navigate,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward,
                onReload = viewModel::reload,
                onStop = viewModel::stop,
                onNewTab = viewModel::createTab,
                onMinimize = {
                    runWithOverlayPermission(::minimizeNow)
                },
                onUserAgentMode = viewModel::setUserAgentMode,
                onSessions = { showSessionManager = true },
            )
            TabStrip(
                tabs = session.tabs.filter { it.presentationState == PresentationState.BROWSER },
                onActivate = viewModel::activate,
                onClose = viewModel::close,
            )
            if (page.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            session.navigationError?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            HorizontalDivider()
            BrowserViewport(webView)
        }
    }
}

@Composable
private fun BrowserToolbar(
    page: EnginePageState,
    session: BrowserSessionState,
    onNavigate: (String) -> Unit,
    onBack: () -> Boolean,
    onForward: () -> Boolean,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onNewTab: () -> Unit,
    onMinimize: () -> Unit,
    onUserAgentMode: (TabId, UserAgentMode) -> Unit,
    onSessions: () -> Unit,
) {
    var omnibox by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val activeTab = session.tabs.firstOrNull(Tab::selected)
    LaunchedEffect(page.url) {
        if (page.url.isNotBlank()) omnibox = page.url
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = page.canGoBack,
                onClick = { onBack() },
                modifier = Modifier.semantics { contentDescription = "Back" },
            ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            IconButton(
                enabled = page.canGoForward,
                onClick = { onForward() },
                modifier = Modifier.semantics { contentDescription = "Forward" },
            ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
            if (page.loading) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.semantics { contentDescription = "Stop loading" },
                ) { Text("×") }
            } else {
                IconButton(
                    onClick = onReload,
                    modifier = Modifier.semantics { contentDescription = "Reload" },
                ) { Text("↻") }
            }
            page.favicon?.let { favicon ->
                Image(
                    bitmap = favicon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(4.dp))
            }
            Text(
                text = page.title.takeIf { it.isNotBlank() } ?: "Bubble",
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = onMinimize,
                modifier = Modifier.semantics { contentDescription = "Minimize tab to floating head" },
            ) { Text("●") }
            TextButton(onClick = onNewTab) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                text = session.tabs.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        OutlinedTextField(
            value = omnibox,
            onValueChange = { omnibox = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (page.url.startsWith("http://")) "Insecure HTTP" else "Search or address") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    onNavigate(omnibox)
                },
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAgentSwitcher(
                mode = activeTab?.userAgentMode ?: UserAgentMode.MOBILE,
                enabled = activeTab != null,
                onMode = { mode -> activeTab?.let { onUserAgentMode(it.id, mode) } },
            )
            TextButton(onClick = onSessions) { Text("Sessions") }
            val headCount = session.tabs.count { it.presentationState == PresentationState.HEAD }
            if (headCount > 0) {
                Text("Heads: $headCount", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun UserAgentSwitcher(
    mode: UserAgentMode,
    enabled: Boolean,
    onMode: (UserAgentMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Text("UA: ${mode.displayName()}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            UserAgentMode.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayName()) },
                    onClick = {
                        expanded = false
                        onMode(choice)
                    },
                )
            }
        }
    }
}

private fun UserAgentMode.displayName(): String = when (this) {
    UserAgentMode.MOBILE -> "Chrome mobile"
    UserAgentMode.DESKTOP -> "Chrome desktop"
    UserAgentMode.SYSTEM -> "System WebView"
}

@Composable
private fun SessionManagerDialog(
    sessions: List<SavedSessionSummary>,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRestore: (String, SavedSessionRestoreMode) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved sessions") },
        text = {
            Column {
                TextButton(onClick = onSave) { Text("Save current session") }
                if (sessions.isEmpty()) {
                    Text(
                        "No saved sessions yet.",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(sessions, key = SavedSessionSummary::id) { saved ->
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(saved.name, style = MaterialTheme.typography.titleSmall)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            onRestore(saved.id, SavedSessionRestoreMode.REPLACE)
                                        },
                                    ) { Text("Replace") }
                                    TextButton(
                                        onClick = {
                                            onRestore(saved.id, SavedSessionRestoreMode.MERGE)
                                        },
                                    ) { Text("Merge") }
                                    TextButton(
                                        onClick = {
                                            onRestore(saved.id, SavedSessionRestoreMode.ADD_ALL)
                                        },
                                    ) { Text("Add all") }
                                    TextButton(onClick = { onDelete(saved.id) }) {
                                        Text("Delete")
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun TabStrip(
    tabs: List<Tab>,
    onActivate: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            Surface(
                color = if (tab.selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                onClick = { onActivate(tab.id) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tab.title.takeIf { it.isNotBlank() } ?: "New tab",
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp, top = 7.dp, bottom = 7.dp),
                    )
                    IconButton(
                        onClick = { onClose(tab.id) },
                        modifier = Modifier
                            .size(36.dp)
                            .semantics { contentDescription = "Close ${tab.title}" },
                    ) { Text("×") }
                }
            }
        }
    }
}

@Composable
private fun BrowserViewport(webView: WebView?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (webView == null) {
            CircularProgressIndicator()
        } else {
            key(webView) {
                AndroidView(
                    factory = {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
