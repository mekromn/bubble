package com.mekromn.bubble

import android.app.Application
import com.mekromn.bubble.data.AppContainer

class BubbleApplication : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var runtime: BubbleRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runtime = BubbleRuntime(this, container)
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
