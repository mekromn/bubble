package com.mekromn.bubble.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab
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

/**
 * High-refresh browser chrome. Gecko owns the page layer directly in BrowserActivity; this
 * composable stays transparent through the page region during ordinary browsing and animates only
 * lightweight chrome/overlays. The Activity uses [onModalInteractionChanged] to decide when the
 * full-screen Compose layer should intercept page-region touches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreenV2(
    viewModel: BrowserViewModel,
    onModalInteractionChanged: (Boolean) -> Unit = {},
) {
    val session by viewModel.sessionState.collectAsState()
    val page by viewModel.pageState.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState(initial = emptyList())
    val settings by viewModel.settings.collectAsState(initial = BrowserSettings())
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showSaveSession by remember { mutableStateOf(false) }
    var showOverlayExplanation by remember { mutableStateOf(false) }
    var pendingOverlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var sessionName by remember { mutableStateOf("") }
    var minimizing by remember { mutableStateOf(false) }

    val activeTab = session.tabs.firstOrNull(Tab::selected)
    val headCount = session.tabs.count { it.presentationState == PresentationState.HEAD }
    val modalInteraction =
        showTabs || showMenu || showSessions || showSaveSession || showOverlayExplanation

    LaunchedEffect(modalInteraction) {
        onModalInteractionChanged(modalInteraction)
    }
    DisposableEffect(Unit) {
        onDispose { onModalInteractionChanged(false) }
    }

    val minimizeScale by animateFloatAsState(
        targetValue = if (minimizing) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "minimizeScale",
    )

    fun minimizeNow() {
        if (minimizing) return
        minimizing = true
        viewModel.minimizeActiveToHead { minimizedTab ->
            minimizing = false
            if (minimizedTab == null) return@minimizeActiveToHead
            if (FloatingHeadService.start(context)) {
                // Explicit user action only. Persisted state must never background the task.
                activity?.moveTaskToBack(true)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Android could not start the floating bubble. Bubble stayed open.",
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
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingOverlayAction
        pendingOverlayAction = null
        if (Settings.canDrawOverlays(context)) action?.invoke()
        else scope.launch { snackbarHostState.showSnackbar("Floating bubble permission was not granted.") }
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
            Unit
        }
    }

    if (showOverlayExplanation) {
        AlertDialog(
            onDismissRequest = {
                showOverlayExplanation = false
                pendingOverlayAction = null
            },
            title = { Text("Allow floating bubbles") },
            text = {
                Text("Bubble uses display-over-other-apps only for browser or AI-chat bubbles you explicitly minimize.")
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
                    shape = RoundedCornerShape(22.dp),
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
        PremiumMenuSheet(
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
        SavedWorkspacesSheet(
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
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = !showTabs,
                enter = slideInVertically(tween(145)) { -it / 3 } + fadeIn(tween(105)),
                exit = slideOutVertically(tween(100)) { -it / 3 } + fadeOut(tween(85)),
            ) {
                PremiumOmnibox(
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
                enter = slideInVertically(tween(165)) { it / 2 } + fadeIn(tween(115)),
                exit = slideOutVertically(tween(100)) { it / 2 } + fadeOut(tween(80)),
            ) {
                FloatingBrowserToolbar(
                    canGoBack = page.canGoBack,
                    canGoForward = page.canGoForward,
                    tabCount = session.tabs.size,
                    headCount = headCount,
                    minimizeScale = minimizeScale,
                    onBack = { viewModel.goBack() },
                    onForward = { viewModel.goForward() },
                    onNewTab = viewModel::createTab,
                    onMinimize = { withOverlayPermission(::minimizeNow) },
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
            BrowserStatusOverlay(
                page = page,
                navigationError = session.navigationError,
            )

            AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn(tween(125)) + scaleIn(tween(165), initialScale = 0.985f),
                exit = fadeOut(tween(95)) + scaleOut(tween(115), targetScale = 0.99f),
            ) {
                PremiumTabSwitcher(
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
private fun PremiumOmnibox(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .height(54.dp),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search or enter address") },
            leadingIcon = {
                Icon(
                    imageVector = if (page.url.startsWith("http://")) {
                        Icons.Rounded.Warning
                    } else {
                        Icons.Rounded.Language
                    },
                    contentDescription = null,
                )
            },
            trailingIcon = {
                IconButton(onClick = if (page.loading) onStop else onReload) {
                    Icon(
                        if (page.loading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                        contentDescription = if (page.loading) "Stop" else "Reload",
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 7.dp,
        ) {
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Browser menu")
            }
        }
    }
}

@Composable
private fun FloatingBrowserToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    headCount: Int,
    minimizeScale: Float,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onMinimize: () -> Unit,
    onTabs: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 5.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
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
                    Icon(Icons.Rounded.Add, contentDescription = "New ChatGPT tab")
                }
                BadgedBox(
                    badge = {
                        if (headCount > 0) Badge { Text(headCount.coerceAtMost(99).toString()) }
                    },
                ) {
                    FilledTonalIconButton(
                        onClick = onMinimize,
                        modifier = Modifier.graphicsLayer {
                            scaleX = minimizeScale
                            scaleY = minimizeScale
                        },
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = "Minimize to bubble")
                    }
                }
                BadgedBox(
                    badge = { Badge { Text(tabCount.coerceAtMost(99).toString()) } },
                ) {
                    IconButton(onClick = onTabs) {
                        Icon(Icons.Rounded.Tab, contentDescription = "Tabs")
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumTabSwitcher(
    tabs: List<Tab>,
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
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close tab switcher")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tabs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${tabs.size} open",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onNewTab) {
                    Icon(Icons.Rounded.Add, contentDescription = "New tab")
                }
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                placeholder = { Text("Find a tab") },
                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id.value }) { tab ->
                    PremiumTabCard(tab, onActivate, onClose)
                }
            }
        }
    }
}

@Composable
private fun PremiumTabCard(
    tab: Tab,
    onActivate: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
) {
    val host = remember(tab.lastCommittedUrl) { hostForDisplay(tab.lastCommittedUrl) }
    Card(
        onClick = { onActivate(tab.id) },
        shape = RoundedCornerShape(26.dp),
        border = if (tab.selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (tab.selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (tab.selected) 8.dp else 3.dp),
        modifier = Modifier.heightIn(min = 176.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp),
                    )
                }
                IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close ${tab.title}")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                tab.title.ifBlank { "New tab" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                host.ifBlank { "New tab" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tab.keepRendererAlive) StatusPill("Live")
                if (tab.presentationState == PresentationState.HEAD) StatusPill("Bubble")
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumMenuSheet(
    activeTab: Tab?,
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
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            "Bubble",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Text(
            "Fast browser controls",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
        ListItem(
            headlineContent = { Text("New ChatGPT tab") },
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

        SheetSectionTitle("Site mode")
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

        SheetSectionTitle("Display smoothness")
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
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedWorkspacesSheet(
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
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onSave) { Text("Save current") }
        }
        if (sessions.isEmpty()) {
            Text(
                "No saved workspaces yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(22.dp),
            )
        } else {
            Column(modifier = Modifier.padding(bottom = 22.dp)) {
                sessions.forEach { saved ->
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
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
    UserAgentMode.MOBILE -> "Mobile"
    UserAgentMode.DESKTOP -> "Desktop"
    UserAgentMode.SYSTEM -> "Gecko default"
}

private fun RefreshRateMode.displayName(): String = when (this) {
    RefreshRateMode.AUTO -> "Auto"
    RefreshRateMode.HZ_60 -> "60 Hz"
    RefreshRateMode.HZ_90 -> "90 Hz"
    RefreshRateMode.HZ_120_PLUS -> "120+ Hz"
    RefreshRateMode.HIGHEST -> "Maximum"
}

private fun hostForDisplay(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.").orEmpty()
}.getOrDefault("")

private fun sharePage(context: Context, url: String?) {
    if (url.isNullOrBlank() || url == "about:blank") return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share page")) }
}
