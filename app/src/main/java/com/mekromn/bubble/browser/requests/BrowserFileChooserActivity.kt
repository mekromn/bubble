package com.mekromn.bubble.browser.requests

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

class BrowserFileChooserActivity : ComponentActivity() {
    private var launched = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            extractSafeUris(result.data)
        } else {
            null
        }
        BrowserFileChooserBroker.complete(uris)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    BrowserFileChooserBroker.complete(null)
                    finish()
                }
            },
        )
        if (savedInstanceState == null) launchPicker()
    }

    private fun launchPicker() {
        if (launched) return
        launched = true
        val acceptTypes = intent.getStringArrayExtra(EXTRA_ACCEPT_TYPES)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
        val allowMultiple = intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false)

        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptTypes.size == 1) acceptTypes.first() else "*/*"
            if (acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
        picker.launch(pickerIntent)
    }

    private fun extractSafeUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val candidates = buildList {
            data.data?.let(::add)
            val clip: ClipData? = data.clipData
            if (clip != null) {
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }
        val safe = candidates
            .filter { it.scheme.equals("content", true) }
            .distinct()
        return safe.takeIf(List<Uri>::isNotEmpty)?.toTypedArray()
    }

    companion object {
        const val EXTRA_ACCEPT_TYPES = "com.mekromn.bubble.extra.ACCEPT_TYPES"
        const val EXTRA_ALLOW_MULTIPLE = "com.mekromn.bubble.extra.ALLOW_MULTIPLE"
    }
}
