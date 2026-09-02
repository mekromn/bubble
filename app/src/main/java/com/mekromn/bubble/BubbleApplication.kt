package com.mekromn.bubble

import android.app.Application
import com.mekromn.bubble.data.AppContainer
import com.mekromn.bubble.data.settings.BrowserSettingsRepository
import com.mekromn.bubble.display.HighRefreshRateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BubbleApplication : Application() {
    /**
     * 0.4.4 browser isolation rule: launching the browser must not initialize Bubble's legacy
     * tab/database/AI/renderer stack. Those objects are created only if an older feature explicitly
     * asks for them.
     */
    val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) { AppContainer(this) }

    private val runtimeDelegate = lazy(LazyThreadSafetyMode.NONE) {
        BubbleRuntime(this, container)
    }
    val runtime: BubbleRuntime
        get() = runtimeDelegate.value

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var highRefreshRateController: HighRefreshRateController
        private set

    override fun onCreate() {
        super.onCreate()

        // Keep only the inexpensive display policy alive during the isolated browser bring-up.
        // BrowserSettingsRepository uses DataStore and does not touch the Room/session stack.
        highRefreshRateController = HighRefreshRateController(
            application = this,
            settings = BrowserSettingsRepository(this),
            scope = applicationScope,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (runtimeDelegate.isInitialized()) runtime.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (runtimeDelegate.isInitialized()) runtime.onLowMemory()
    }
}
