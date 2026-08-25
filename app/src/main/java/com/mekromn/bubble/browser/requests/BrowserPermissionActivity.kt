package com.mekromn.bubble.browser.requests

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

class BrowserPermissionActivity : ComponentActivity() {
    private var dialogShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        BrowserPermissionBroker.complete(result.filterValues { it }.keys)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    BrowserPermissionBroker.deny()
                    finish()
                }
            },
        )
        showPromptIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        showPromptIfNeeded()
    }

    private fun showPromptIfNeeded() {
        if (dialogShown) return
        val prompt = BrowserPermissionBroker.currentPrompt() ?: run {
            finish()
            return
        }
        dialogShown = true
        val origin = prompt.origin.ifBlank { "This website" }
        AlertDialog.Builder(this)
            .setTitle("Allow website permission?")
            .setMessage("$origin\n\nRequests: ${prompt.labels.joinToString()}")
            .setPositiveButton("Allow") { _, _ ->
                if (prompt.androidPermissions.isEmpty()) {
                    BrowserPermissionBroker.deny()
                    finish()
                } else {
                    permissionLauncher.launch(prompt.androidPermissions.toTypedArray())
                }
            }
            .setNegativeButton("Block") { _, _ ->
                BrowserPermissionBroker.deny()
                finish()
            }
            .setOnCancelListener {
                BrowserPermissionBroker.deny()
                finish()
            }
            .show()
    }
}
