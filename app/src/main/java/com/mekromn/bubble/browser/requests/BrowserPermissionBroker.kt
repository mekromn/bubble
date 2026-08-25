package com.mekromn.bubble.browser.requests

import android.Manifest
import android.content.Context
import android.content.Intent
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest

object BrowserPermissionBroker {
    data class Prompt(
        val origin: String,
        val labels: List<String>,
        val androidPermissions: List<String>,
    )

    private sealed interface Pending {
        val origin: String

        data class Media(
            val request: PermissionRequest,
            val webToAndroid: Map<String, String>,
        ) : Pending {
            override val origin: String = request.origin?.toString().orEmpty()
        }

        data class Geolocation(
            override val origin: String,
            val callback: GeolocationPermissions.Callback,
        ) : Pending
    }

    @Volatile
    private var pending: Pending? = null

    @Synchronized
    fun requestMedia(context: Context, request: PermissionRequest) {
        replacePending(null)
        val mapping = buildMap {
            request.resources.forEach { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> put(resource, Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> put(resource, Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        if (mapping.isEmpty()) {
            request.deny()
            return
        }
        pending = Pending.Media(request, mapping)
        launchPromptActivity(context)
    }

    @Synchronized
    fun requestGeolocation(
        context: Context,
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        replacePending(null)
        pending = Pending.Geolocation(origin, callback)
        launchPromptActivity(context)
    }

    @Synchronized
    fun currentPrompt(): Prompt? = when (val current = pending) {
        null -> null
        is Pending.Media -> Prompt(
            origin = current.origin,
            labels = current.webToAndroid.keys.map { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "Camera"
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "Microphone"
                    else -> "Web permission"
                }
            },
            androidPermissions = current.webToAndroid.values.distinct(),
        )
        is Pending.Geolocation -> Prompt(
            origin = current.origin,
            labels = listOf("Location"),
            androidPermissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    @Synchronized
    fun complete(grantedAndroidPermissions: Set<String>) {
        val current = pending
        pending = null
        when (current) {
            null -> Unit
            is Pending.Media -> {
                val allowedResources = current.webToAndroid
                    .filterValues { it in grantedAndroidPermissions }
                    .keys
                    .toTypedArray()
                if (allowedResources.isEmpty()) current.request.deny()
                else current.request.grant(allowedResources)
            }
            is Pending.Geolocation -> {
                val allowed = Manifest.permission.ACCESS_FINE_LOCATION in grantedAndroidPermissions ||
                    Manifest.permission.ACCESS_COARSE_LOCATION in grantedAndroidPermissions
                current.callback.invoke(current.origin, allowed, false)
            }
        }
    }

    @Synchronized
    fun deny() {
        replacePending(null)
    }

    @Synchronized
    fun cancel(request: PermissionRequest) {
        val current = pending
        if (current is Pending.Media && current.request === request) {
            pending = null
        }
    }

    private fun launchPromptActivity(context: Context) {
        val launched = runCatching {
            context.startActivity(
                Intent(context, BrowserPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!launched) deny()
    }

    private fun replacePending(next: Pending?) {
        when (val old = pending) {
            is Pending.Media -> old.request.deny()
            is Pending.Geolocation -> old.callback.invoke(old.origin, false, false)
            null -> Unit
        }
        pending = next
    }
}
