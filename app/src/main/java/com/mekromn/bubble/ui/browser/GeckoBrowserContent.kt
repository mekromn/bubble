package com.mekromn.bubble.ui.browser

import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mekromn.bubble.browser.engine.EnginePageState

/**
 * Engine-neutral browser surface used by Gecko-backed tabs.
 *
 * BrowserScreen predates the Gecko migration and still contains a private WebView-specific
 * overload. RendererPool now exposes View?, so this overload is the correct static match for
 * GeckoView and prevents the new engine from being lost behind the old WebView-only boundary.
 */
@Composable
internal fun BrowserContent(
    webView: View?,
    page: EnginePageState,
    navigationError: String?,
) {
    val hostContext = LocalContext.current
    val newTab = page.url.isBlank() || page.url == "about:blank"

    DisposableEffect(webView, hostContext) {
        val wrapper = webView?.context as? MutableContextWrapper
        wrapper?.setBaseContext(hostContext)
        onDispose {
            wrapper?.setBaseContext(hostContext.applicationContext)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            newTab -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Bubble", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Search or type an address above.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            webView == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            else -> {
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

        AnimatedVisibility(
            visible = page.loading,
            enter = expandVertically(tween(130)) + fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val errorText = when {
            navigationError != null -> navigationError
            page.error != null -> buildString {
                append("Page load failed")
                append(" (code ")
                append(page.error.code)
                append(", category ")
                append(page.error.category)
                append(')')
            }
            else -> null
        }

        errorText?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
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
