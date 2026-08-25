package com.mekromn.bubble

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.bubble.browser.engine.EnginePageState
import com.mekromn.bubble.browser.session.BrowserSessionState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.heads.service.FloatingHeadService
import com.mekromn.bubble.ui.browser.BrowserViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    companion object {
        const val EXTRA_RESTORE_TAB_ID = "com.mekromn.bubble.extra.RESTORE_TAB_ID"
    }
}

@Composable
private fun BrowserScreen(viewModel: BrowserViewModel) {
    val session by viewModel.sessionState.collectAsState()
    val page by viewModel.pageState.collectAsState()
    val webView by viewModel.activeWebView.collectAsState()
    val context = LocalContext.current
    var explainOverlay by remember { mutableStateOf(false) }
    var minimizeAfterPermission by remember { mutableStateOf(false) }

    fun minimizeNow() {
        viewModel.minimizeActiveToHead {
            FloatingHeadService.start(context)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (minimizeAfterPermission && Settings.canDrawOverlays(context)) minimizeNow()
        minimizeAfterPermission = false
    }

    if (explainOverlay) {
        AlertDialog(
            onDismissRequest = { explainOverlay = false },
            title = { Text("Allow floating heads") },
            text = {
                Text(
                    "Bubble needs Android's display-over-other-apps permission only to show the draggable tab heads you create. The browser works without it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        explainOverlay = false
                        minimizeAfterPermission = true
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { explainOverlay = false }) { Text("Not now") }
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
                    if (Settings.canDrawOverlays(context)) minimizeNow() else explainOverlay = true
                },
            )
            TabStrip(
                tabs = session.tabs.filter { it.presentationState == com.mekromn.bubble.browser.session.PresentationState.BROWSER },
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
) {
    var omnibox by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(page.url) {
        if (page.url.isNotBlank()) omnibox = page.url
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
    }
}

@Composable
private fun TabStrip(
    tabs: List<Tab>,
    onActivate: (com.mekromn.bubble.browser.session.TabId) -> Unit,
    onClose: (com.mekromn.bubble.browser.session.TabId) -> Unit,
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
