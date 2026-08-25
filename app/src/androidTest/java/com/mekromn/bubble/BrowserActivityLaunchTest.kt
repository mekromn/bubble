package com.mekromn.bubble

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserActivityLaunchTest {
    @Test
    fun browserActivityLaunches() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing)
            }
        }
    }
}
