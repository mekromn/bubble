package com.mekromn.bubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.bubble.browser.navigation.SharedUrlExtractor
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.ui.browser.BrowserScreen
import com.mekromn.bubble.ui.browser.BrowserViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BrowserActivity : ComponentActivity() {
    private var notificationPermissionRequestedThisActivity = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            (application as? BubbleApplication)?.runtime?.replyNotifications?.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) dispatchIncomingIntent(intent)
        observeAiWorkspaceForNotificationPermission()
        setContent {
            BubbleTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                ) {
                    val browserViewModel: BrowserViewModel = viewModel()
                    BrowserScreen(browserViewModel)
                }
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
                runCatching {
                    val tabId = TabId(rawId)
                    sessions.activate(tabId)
                    app.runtime.aiWorkspaces.markRead(tabId)
                }
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

    private fun observeAiWorkspaceForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val app = application as BubbleApplication
                app.runtime.aiWorkspaces.state
                    .map { state -> state.workspaces.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { hasWorkspace ->
                        if (hasWorkspace) requestNotificationPermissionIfNeeded()
                    }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequestedThisActivity) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            (application as BubbleApplication).runtime.replyNotifications.refresh()
            return
        }
        notificationPermissionRequestedThisActivity = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_RESTORE_TAB_ID = "com.mekromn.bubble.extra.RESTORE_TAB_ID"
        const val EXTRA_AI_REPLY_GENERATION = "com.mekromn.bubble.extra.AI_REPLY_GENERATION"
    }
}
