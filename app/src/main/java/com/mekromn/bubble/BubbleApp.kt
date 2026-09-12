package com.mekromn.bubble

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** App bootstrap stays browser-light: diagnostics + notification channels, no GeckoRuntime/Workspace creation. */
class BubbleApp : Application(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // Install before GeckoRuntime/Workspace are ever created so startup/session-open failures and
        // the next-launch ApplicationExitInfo record are captured as early as Android allows.
        if (DiagnosticLog.ENABLED) DiagnosticLog.install(this)
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("APP", "BubbleApp.onCreate begin")
        NotificationHealth.prepare(this)
        UploadStaging.io.execute { ArchiveCache.cleanup(this) }
        registerActivityLifecycleCallbacks(this)
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("APP", "BubbleApp.onCreate complete")
    }

    override fun onActivityResumed(activity: Activity) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "resumed=${activity.javaClass.simpleName} ${DiagnosticLog.selectedState()}")
        if (activity is BrowserActivity) main.post {
            NotificationHealth.maybePrompt(activity)
            Workspace.peek()?.let { VoiceTitleFallback.attach(this, it) }
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "created=${activity.javaClass.simpleName} restored=${state != null}")
    }
    override fun onActivityStarted(activity: Activity) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "started=${activity.javaClass.simpleName}")
    }
    override fun onActivityPaused(activity: Activity) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "paused=${activity.javaClass.simpleName} ${DiagnosticLog.selectedState()}")
    }
    override fun onActivityStopped(activity: Activity) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "stopped=${activity.javaClass.simpleName}")
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "saveState=${activity.javaClass.simpleName}")
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (DiagnosticLog.ENABLED) DiagnosticLog.event("ACTIVITY", "destroyed=${activity.javaClass.simpleName}")
    }
}
