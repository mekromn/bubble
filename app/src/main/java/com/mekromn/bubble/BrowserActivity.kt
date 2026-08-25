package com.mekromn.bubble

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mekromn.bubble.browser.navigation.SharedUrlExtractor
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.ui.browser.BrowserScreen
import com.mekromn.bubble.ui.browser.BrowserViewModel
import kotlinx.coroutines.launch

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) dispatchIncomingIntent(intent)
        setContent {
            BubbleTheme {
                val browserViewModel: BrowserViewModel = viewModel()
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
