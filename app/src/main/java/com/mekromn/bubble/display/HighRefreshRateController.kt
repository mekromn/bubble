package com.mekromn.bubble.display

import android.app.Activity
import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import com.mekromn.bubble.data.settings.BrowserSettingsRepository
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Applies Bubble's persisted high-refresh policy to every foreground app window.
 * Android remains authoritative and may lower the actual display rate for thermal,
 * power, multi-window or compositor reasons.
 */
class HighRefreshRateController(
    private val application: Application,
    settings: BrowserSettingsRepository,
    scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private val resumedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var currentMode: RefreshRateMode = RefreshRateMode.HZ_120_PLUS

    init {
        application.registerActivityLifecycleCallbacks(this)
        scope.launch {
            settings.settings
                .map { it.refreshRateMode }
                .distinctUntilChanged()
                .collect { mode ->
                    currentMode = mode
                    resumedActivities.toList().forEach { activity -> apply(activity, mode) }
                }
        }
    }

    fun apply(activity: Activity, mode: RefreshRateMode = currentMode) {
        val display = activityDisplay(activity)
        val supportedRates = display?.supportedModes?.map { it.refreshRate }.orEmpty()
        val requestedRate = RefreshRatePolicy.resolveSupportedRate(mode, supportedRates)

        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        attributes.preferredRefreshRate = requestedRate
        activity.window.attributes = attributes

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val requestedFrameRate = if (mode == RefreshRateMode.AUTO) {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            } else {
                requestedRate
            }
            (activity.window.decorView as? ViewGroup)?.propagateRequestedFrameRate(
                requestedFrameRate,
                true,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val requestedFrameRate = if (mode == RefreshRateMode.AUTO) {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            } else {
                requestedRate
            }
            activity.window.decorView.setRequestedFrameRate(requestedFrameRate)
        }
    }

    private fun activityDisplay(activity: Activity): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.let { return it }
        }
        return activity.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities += activity
        apply(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities -= activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities -= activity
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
