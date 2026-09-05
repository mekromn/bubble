plugins {
    id("com.android.application") version "9.3.0" apply false
    // Upgrade AGP's built-in Kotlin compiler to match the dependencies' metadata.
    // The app uses built-in Kotlin; this plugin is NOT applied to the app module.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
