package com.mekromn.bubble.ui.browser

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.session.BrowserSessionState
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab as BrowserTab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import com.mekromn.bubble.data.db.SavedSessionRestoreMode
import com.mekromn.bubble.data.db.SavedSessionSummary
import com.mekromn.bubble.data.settings.BrowserSettings
import com.mekromn.bubble.display.RefreshRateMode
import com.mekromn.bubble.heads.service.FloatingHeadService
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val session by viewModel.sessionState.collectAsState()
    val page by viewModel.pageState.collectAsState()
    val webView by viewModel.activeWebView.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState(initial = emptyList())
    val settings by viewModel.settings.collectAsState(initial = BrowserSettings())
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = rememberSnackbarHostState()

    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showSaveSession by remember { mutableStateOf(false) }
    var showOverlayExplanation by remember { mutableStateOf(false) }
    var pendingOverlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var sessionName by remember { mutableStateOf("") }
    var minimizing by remember { mutableStateOf(false) }

    val activeTab = session.tabs.firstOrNull(BrowserTab::selected)
    val headCount = session.tabs.count { it.presentationState == PresentationState.HEAD }
    val headScale by animateFloatAsState(
        targetValue = if (minimizing) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "head action scale",
    )

    fun minimizeNow() {
        if (minimizing) return
        minimizing = true
        viewModel.minimizeActiveToHead {
            minimizing = false
            if (!FloatingHeadService.start(context)) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Android blocked the floating-head service. The tab is safe in Bubble's tab overview.",
                    )
                }
            }
        }
    }

    fun withOverlayPermission(action: () -> Unit) {
        if (Settings.canDrawOverlays(context)) action()
        else {
            pendingOverlayAction = action
            showOverlayExplanation = true
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingOverlayAction
        pendingOverlayAction = null
        if (Settings.canDrawOverlays(context)) action?.invoke()
        else scope.launch { snackbarHostState.showSnackbar("Floating heads permission was not granted.") }
    }

    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect()
            when {
                showTabs -> showTabs = false
                showMenu -> showMenu = false
                showSessions -> showSessions = false
                page.canGoBack && viewModel.goBack() -> Unit
                else -> activity?.finish()
            }
        } catch (_: CancellationException) {
            // A cancelled predictive-back gesture must not navigate or close a surface.
        }
    }

    if (showOverlayExplanation) {
        AlertDialog(
            onDismissRequest = {
                showOverlayExplanation = false
                pendingOverlayAction = null
            },
            title = { Text("Allow floating heads") },
            text = {
                Text(
                    "Bubble only needs display-over-other-apps permission for the draggable heads you explicitly create.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayExplanation = false
                        overlayPermissionLauncher.launch(
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
                        showOverlayExplanation = false
                        pendingOverlayAction = null
                    },
                ) { Text("Not now") }
            },
        )
    }

    if (showSaveSession) {
        AlertDialog(
            onDismissRequest = { showSaveSession = false },
            title = { Text("Save workspace") },
            text = {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    singleLine = true,
                    label = { Text("Workspace name") },
                    shape = RoundedCornerShape(18.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = sessionName.isNotBlank(),
                    onClick = {
                        showSaveSession = false
                        viewModel.saveCurrentSession(sessionName)
                        sessionName = ""
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveSession = false }) { Text("Cancel") }
            },
        )
    }

    if (showMenu) {
        BrowserMenuSheet(
            activeTab = activeTab,
            settings = settings,
            onDismiss = { showMenu = false },
            onNewTab = {
                showMenu = false
                viewModel.createTab()
            },
            onSessions = {
                showMenu = false
                showSessions = true
            },
            onShare = {
                showMenu = false
                sharePage(context, activeTab?.lastCommittedUrl)
            },
            onUserAgent = { mode -> activeTab?.let { viewModel.setUserAgentMode(it.id, mode) } },
            onRefreshRate = viewModel::setRefreshRateMode,
        )
    }

    if (showSessions) {
        SavedSessionsSheet(
            sessions = savedSessions,
            onDismiss = { showSessions = false },
            onSave = {
                showSessions = false
                showSaveSession = true
            },
            onRestore = { id, mode ->
                showSessions = false
                viewModel.restoreSavedSession(id, mode) { restored ->
                    if (restored && viewModel.sessionState.value.tabs.any {
                            it.presentationState == PresentationState.HEAD
                        }
                    ) {
                        withOverlayPermission { FloatingHeadService.start(context) }
                    }
                }
            },
            onDelete = viewModel::deleteSavedSession,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = !showTabs,
                enter = slideInVertically(tween(180)) { -it / 2 } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(130)) { -it / 2 } + fadeOut(tween(120)),
            ) {
                AddressBar(
                    page = page,
                    onNavigate = viewModel::navigate,
                    onReload = viewModel::reload,
                    onStop = viewModel::stop,
                    onMenu = { showMenu = true },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !showTabs,
                enter = slideInVertically(tween(190)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(140)) { it } + fadeOut(tween(110)),
            ) {
                PhoneBottomToolbar(
                    canGoBack = page.canGoBack,
                    canGoForward = page.canGoForward,
                    tabCount = session.tabs.size,
                    headCount = headCount,
                    headScale = headScale,
                    onBack = { viewModel.goBack() },
                    onForward = { viewModel.goForward() },
                    onNewTab = viewModel::createTab,
                    onHead = { withOverlayPermission(::minimizeNow) },
                    onTabs = { showTabs = true },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BrowserContent(
                webView = webView,
                page = page,
                navigationError = session.navigationError,
            )

            AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn(tween(170)) + scaleIn(tween(220), initialScale = 0.965f),
                exit = fadeOut(tween(130)) + scaleOut(tween(150), targetScale = 0.985f),
            ) {
                TabOverview(
                    tabs = session.tabs,
                    onDismiss = { showTabs = false },
                    onActivate = { id ->
                        viewModel.activate(id)
                        showTabs = false
                    },
                    onClose = viewModel::close,
                    onNewTab = {
                        viewModel.createTab()
                        showTabs = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AddressBar(
    page: EnginePageState,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(page.url) {
        if (page.url.isNotBlank() && page.url != "about:blank") text = page.url
        else if (!page.loading) text = ""
    }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 52.dp),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search or type address") },
                leadingIcon = {
                    Icon(
                        imageVector = if (page.url.startsWith("http://")) Icons.Rounded.Warning else Icons.Rounded.Language,
                        contentDescription = if (page.url.startsWith("http://")) "Insecure HTTP" else "Website",
                    )
                },
                trailingIcon = {
                    IconButton(onClick = if (page.loading) onStop else onReload) {
                        Icon(
                            imageVector = if (page.loading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                            contentDescription = if (page.loading) "Stop loading" else "Reload",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        onNavigate(text)
                    },
                ),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            IconButton(
                onClick = onMenu,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Browser menu")
            }
        }
    }
}

@Composable
private fun PhoneBottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    headCount: Int,
    headScale: Float,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onHead: () -> Unit,
    onTabs: () -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = canGoBack, onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            IconButton(enabled = canGoForward, onClick = onForward) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
            }
            FilledTonalIconButton(onClick = onNewTab) {
                Icon(Icons.Rounded.Add, contentDescription = "New tab")
            }
            BadgedBox(
                badge = {
                    if (headCount > 0) Badge { Text(headCount.coerceAtMost(99).toString()) }
                },
            ) {
                IconButton(
                    onClick = onHead,
                    modifier = Modifier.graphicsLayer {
                        scaleX = headScale
                        scaleY = headScale
                    },
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = "Minimize to floating head")
                }
            }
            BadgedBox(
                badge = { Badge { Text(tabCount.coerceAtMost(99).toString()) } },
            ) {
                IconButton(onClick = onTabs) {
                    Icon(Icons.Rounded.Tab, contentDescription = "Open tabs")
                }
            }
        }
    }
}

@Composable
private fun BrowserContent(
    webView: WebView?,
    page: EnginePageState,
    navigationError: String?,
) {
    val newTab = page.url.isBlank() || page.url == "about:blank"
    Box(modifier = Modifier.fillMaxSize()) {
        if (newTab) {
            NewTabLanding()
        } else if (webView == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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

        AnimatedVisibility(
            visible = page.loading,
            enter = expandVertically(tween(130)) + fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        navigationError?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun NewTabLanding() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "B",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Bubble", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Browse normally. Minimize anything worth keeping into a floating head.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TabOverview(
    tabs: List<BrowserTab>,
    onDismiss: () -> Unit,
    onActivate: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
    onNewTab: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(tabs, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) tabs
        else tabs.filter { tab ->
            tab.title.lowercase().contains(normalized) ||
                tab.lastCommittedUrl.lowercase().contains(normalized)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close tab overview")
                }
                Text(
                    "Tabs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                FilledTonalIconButton(onClick = onNewTab, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Add, contentDescription = "New tab")
                }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search tabs") },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id.value }) { tab ->
                    TabCard(tab = tab, onActivate = onActivate, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    onActivate: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
) {
    val host = remember(tab.lastCommittedUrl) { hostForDisplay(tab.lastCommittedUrl) }
    Card(
        onClick = { onActivate(tab.id) },
        shape = RoundedCornerShape(24.dp),
        border = if (tab.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (tab.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.heightIn(min = 168.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .size(38.dp)
                        .align(Alignment.CenterStart),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            tab.title.firstOrNull()?.uppercaseChar()?.toString() ?: "B",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                IconButton(
                    onClick = { onClose(tab.id) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(38.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close ${tab.title}")
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = tab.title.ifBlank { "New tab" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = host.ifBlank { "New tab" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.presentationState == PresentationState.HEAD) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        "Floating head",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
    activeTab: BrowserTab?,
    settings: BrowserSettings,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onSessions: () -> Unit,
    onShare: () -> Unit,
    onUserAgent: (UserAgentMode) -> Unit,
    onRefreshRate: (RefreshRateMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Text(
            "Bubble",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text("New tab") },
            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onNewTab),
        )
        ListItem(
            headlineContent = { Text("Saved workspaces") },
            leadingContent = { Icon(Icons.Rounded.Tab, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onSessions),
        )
        ListItem(
            headlineContent = { Text("Share page") },
            leadingContent = { Icon(Icons.Rounded.Share, contentDescription = null) },
            modifier = Modifier.clickable(enabled = activeTab != null, onClick = onShare),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            "Site identity",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserAgentMode.entries.forEach { mode ->
                FilterChip(
                    selected = activeTab?.userAgentMode == mode,
                    enabled = activeTab != null,
                    onClick = { onUserAgent(mode) },
                    label = { Text(mode.displayName()) },
                )
            }
        }

        Text(
            "Display refresh",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RefreshRateMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.refreshRateMode == mode,
                    onClick = { onRefreshRate(mode) },
                    label = { Text(mode.displayName()) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedSessionsSheet(
    sessions: List<SavedSessionSummary>,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRestore: (String, SavedSessionRestoreMode) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onSave) { Text("Save current") }
        }
        if (sessions.isEmpty()) {
            Text(
                "No saved workspaces yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                sessions.forEach { saved ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(saved.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = { onRestore(saved.id, SavedSessionRestoreMode.REPLACE) }) {
                                Text("Replace")
                            }
                            TextButton(onClick = { onRestore(saved.id, SavedSessionRestoreMode.MERGE) }) {
                                Text("Merge")
                            }
                            TextButton(onClick = { onRestore(saved.id, SavedSessionRestoreMode.ADD_ALL) }) {
                                Text("Add all")
                            }
                            TextButton(onClick = { onDelete(saved.id) }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun UserAgentMode.displayName(): String = when (this) {
    UserAgentMode.MOBILE -> "Chrome mobile"
    UserAgentMode.DESKTOP -> "Chrome desktop"
    UserAgentMode.SYSTEM -> "System WebView"
}

private fun RefreshRateMode.displayName(): String = when (this) {
    RefreshRateMode.AUTO -> "Auto"
    RefreshRateMode.HZ_60 -> "60 Hz"
    RefreshRateMode.HZ_90 -> "90 Hz"
    RefreshRateMode.HZ_120_PLUS -> "120+ Hz"
    RefreshRateMode.HIGHEST -> "Max"
}

private fun hostForDisplay(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.").orEmpty()
}.getOrDefault("")

private fun sharePage(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank() || url == "about:blank") return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share page")) }
}
