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
}
