package com.mekromn.bubble

import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Bubble's explicit Gecko runtime profile.
 *
 * This removes GeckoView/browser services Bubble does not use while preserving the subsystems our
 * target workloads require: JavaScript/Wasm, WebGL/WebRender, web fonts, Service Workers, Push,
 * WebRTC, site isolation/Fission defaults, storage, media and Gecko low-memory handling.
 *
 * Keep this policy conservative about security and rendering fidelity. Experimental/breaking speed
 * switches belong in isolated benchmark branches, not here.
 */
internal object GeckoTurboPolicy {
    fun settings(): GeckoRuntimeSettings {
        return GeckoRuntimeSettings.Builder()
            // Logging/debug plumbing is not part of the product. GeckoView debug logging is on by
            // default, so disable it explicitly rather than relying on a build flavor.
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .debugLogging(false)

            // Bubble provides its own browser UI and built-in extension. Do not expose Gecko's
            // preference/add-on-management surfaces or spin a remote extension process for one
            // trusted local extension.
            .aboutConfigEnabled(false)
            .extensionsProcessEnabled(false)
            .extensionsWebAPIEnabled(false)

            // Browser conveniences Bubble does not use. Leaving these off removes work without
            // disabling core web-platform features required by ChatGPT or Google Voice.
            .loginAutofillEnabled(false)
            .enterpriseRootsEnabled(false)
            .webManifest(false)
            .translationsOfferPopup(false)
            .fontInflation(false)
            .inputAutoZoomEnabled(false)

            // Preserve the high-fidelity/full-web path explicitly.
            .javaScriptEnabled(true)
            .webFontsEnabled(true)
            .build()
    }
}
