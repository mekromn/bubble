package com.mekromn.bubble.browser.requests

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

object BrowserFileChooserBroker {
    @Volatile
    private var pending: ValueCallback<Array<Uri>>? = null

    @Synchronized
    fun launch(
        context: Context,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pending?.onReceiveValue(null)
        pending = callback

        val acceptTypes = params.acceptTypes
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toTypedArray()
        val intent = Intent(context, BrowserFileChooserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(BrowserFileChooserActivity.EXTRA_ACCEPT_TYPES, acceptTypes)
            .putExtra(
                BrowserFileChooserActivity.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )

        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    complete(null)
                    false
                },
            )
    }

    @Synchronized
    fun complete(uris: Array<Uri>?) {
        val callback = pending
        pending = null
        callback?.onReceiveValue(uris)
    }
}
