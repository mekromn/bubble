package com.mekromn.bubble.browser.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

class SystemDownloadHandler(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String?,
    ): Long? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription(uri.host.orEmpty())
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        mimeType?.takeIf(String::isNotBlank)?.let(request::setMimeType)
        userAgent?.takeIf(String::isNotBlank)?.let { request.addRequestHeader("User-Agent", it) }
        referer?.takeIf(::isHttpUrl)?.let { request.addRequestHeader("Referer", it) }
        CookieManager.getInstance().getCookie(url)
            ?.takeIf(String::isNotBlank)
            ?.let { request.addRequestHeader("Cookie", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        // API 26-28 intentionally use DownloadManager's managed shared cache until the
        // legacy-storage path is backed by an explicit user permission/SAF flow.

        return runCatching { manager.enqueue(request) }.getOrNull()
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = runCatching { Uri.parse(value).scheme }.getOrNull()
        return scheme.equals("http", true) || scheme.equals("https", true)
    }
}
