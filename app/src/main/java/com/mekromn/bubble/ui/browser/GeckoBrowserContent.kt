package com.mekromn.bubble.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mekromn.bubble.browser.engine.EnginePageState

/**
 * Compose never owns or mounts GeckoView. This layer only paints transient browser status above
 * the native Activity-hosted Gecko surface. Loaded pages therefore have a completely transparent
 * center and Gecko receives pixels/touches directly from Android.
 */
@Composable
internal fun BrowserStatusOverlay(
    page: EnginePageState,
    navigationError: String?,
) {
    val newTab = page.url.isBlank() || page.url == "about:blank"

    Box(modifier = Modifier.fillMaxSize()) {
        if (newTab) NewTabSurface()

        AnimatedVisibility(
            visible = page.loading,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val errorText = when {
            navigationError != null -> navigationError
            page.error != null -> "Page load failed · Gecko ${page.error.code}/${page.error.category}"
            else -> null
        }
        AnimatedVisibility(
            visible = errorText != null,
            enter = fadeIn(tween(130)),
            exit = fadeOut(tween(90)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
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
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
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
}
