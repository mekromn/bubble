package com.mekromn.bubble

import android.app.Application
import com.mekromn.bubble.data.AppContainer
import com.mekromn.bubble.display.HighRefreshRateController

class BubbleApplication : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var runtime: BubbleRuntime
        private set

    lateinit var highRefreshRateController: HighRefreshRateController
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runtime = BubbleRuntime(this, container)
        highRefreshRateController = HighRefreshRateController(
            application = this,
            settings = container.settings,
            scope = runtime.scope,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::runtime.isInitialized) runtime.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::runtime.isInitialized) runtime.onLowMemory()
    }
}
