package com.mekromn.bubble

import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Build-84-proven Gecko runtime baseline.
 *
 * The uploaded Build 84 is the known-fast reference on the target Pixel. Keep runtime policy exactly
 * as that build used it: only remote debugging and console-to-Logcat output are disabled. All other
 * Gecko defaults stay under Gecko's own tuning. Experimental runtime switches must be A/B tested one
 * at a time instead of being accumulated here.
 */
internal object GeckoTurboPolicy {
    fun settings(): GeckoRuntimeSettings = GeckoRuntimeSettings.Builder()
        .remoteDebuggingEnabled(false)
        .consoleOutput(false)
        .build()
}
