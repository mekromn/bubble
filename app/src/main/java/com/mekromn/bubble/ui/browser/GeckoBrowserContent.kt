package com.mekromn.bubble.ui.browser

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mekromn.bubble.browser.engine.EnginePageState

/**
 * The one and only onscreen browser viewport. RendererPool creates the View with the foreground
 * Activity context; Compose only mounts it. No WebView-specific behavior exists in this layer.
 */
@Composable
internal fun BrowserViewport(
    contentView: View?,
    page: EnginePageState,
    navigationError: String?,
) {
    val newTab = page.url.isBlank() || page.url == "about:blank"

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            newTab -> NewTabSurface()
            contentView == null -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    strokeWidth = 2.5.dp,
                )
            }
            else -> {
                key(contentView) {
                    AndroidView(
                        factory = {
                            (contentView.parent as? ViewGroup)?.removeView(contentView)
                            contentView
                        },
                        update = { view ->
                            if (view.parent == null) Unit
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = page.loading,
            enter = fadeIn(tween(90)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val errorText = when {
            navigationError != null -> navigationError
            page.error != null -> "Gecko could not load this page · ${page.error.code}/${page.error.category}"
            else -> null
        }
        AnimatedVisibility(
            visible = errorText != null,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(12.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null)
                    Text(errorText.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun NewTabSurface() {
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
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                Icons.Rounded.Language,
                contentDescription = null,
                modifier = Modifier.padding(22.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Ready when you are",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "Search the web or open another AI chat.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
