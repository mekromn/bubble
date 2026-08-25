package com.mekromn.bubble

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.mekromn.bubble.browser.navigation.SharedUrlExtractor
import com.mekromn.bubble.heads.service.FloatingHeadService
import kotlinx.coroutines.launch

class ShareHeadActivity : ComponentActivity() {
    private var pendingUrl: String? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val url = pendingUrl ?: return@registerForActivityResult finish()
        if (Settings.canDrawOverlays(this)) {
            createHead(url)
        } else {
            showPermissionDeclinedChoice(url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = SharedUrlExtractor.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            finish()
            return
        }
        pendingUrl = url
        if (Settings.canDrawOverlays(this)) {
            createHead(url)
        } else {
            explainOverlayPermission()
        }
    }

    private fun explainOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Allow floating heads")
            .setMessage(
                "Bubble needs Android's display-over-other-apps permission to create the floating head you selected. This permission is used only for user-created browser heads.",
            )
            .setPositiveButton("Continue") { _, _ ->
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri(),
                    ),
                )
            }
            .setNegativeButton("Open normally") { _, _ ->
                pendingUrl?.let(::openNormally) ?: finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showPermissionDeclinedChoice(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Floating heads not allowed")
            .setMessage("You can still open this shared URL in Bubble as a normal browser tab.")
            .setPositiveButton("Open tab") { _, _ -> openNormally(url) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun createHead(url: String) {
        lifecycleScope.launch {
            val app = application as BubbleApplication
            app.runtime.sessions.initialize()
            app.runtime.sessions.createHead(url)
            FloatingHeadService.start(this@ShareHeadActivity)
            finish()
        }
    }

    private fun openNormally(url: String) {
        lifecycleScope.launch {
            val app = application as BubbleApplication
            app.runtime.sessions.initialize()
            app.runtime.sessions.createTab(url)
            startActivity(
                Intent(this@ShareHeadActivity, BrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
