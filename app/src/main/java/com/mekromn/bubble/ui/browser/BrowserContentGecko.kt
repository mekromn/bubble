package com.mekromn.bubble.ui.browser

import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Engine-neutral content surface used when the active renderer is GeckoView. */
@Composable
fun BrowserContent(
    webView: View?,
    page: EnginePageState,
    navigationError: String?,
) {
    val context = LocalContext.current
    val newTab = page.url.isBlank() || page.url == "about:blank"

    DisposableEffect(webView, context) {
        (webView?.context as? MutableContextWrapper)?.setBaseContext(context)
        onDispose {
            (webView?.context as? MutableContextWrapper)?.setBaseContext(context.applicationContext)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (newTab) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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

        val errorText = navigationError ?: page.error?.let { error ->
            "Page failed to load (Gecko error ${error.code}, category ${error.category})."
        }
        errorText?.let { error ->
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
