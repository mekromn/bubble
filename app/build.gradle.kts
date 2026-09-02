plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.mekromn.bubble"
    // Mozilla's current Gecko/Android Components line requires the Android 17.1 compile stubs.
    // Runtime behavior remains targeted to Android 16.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.mekromn.bubble"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Development/test APKs use a repository-pinned key so every CI build has the same
    // signing identity forever. This key is intentionally public and MUST NEVER sign a
    // production/release package. Production signing must use a separate private key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/bubble-debug.jks")
            storePassword = "bubble-debug-2026"
            keyAlias = "bubble-debug"
            keyPassword = "bubble-debug-2026"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
        disable += "OldTargetApi"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.17.0")

    // Use Mozilla's browser-oriented Gecko integration instead of hand-managing GeckoView /
    // GeckoSession as Bubble's primary bring-up path. Android Components owns the defensive
    // Gecko view/session attachment behavior used by Firefox-family Android browsers.
    implementation("org.mozilla.components:browser-engine-gecko:154.0")

    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
