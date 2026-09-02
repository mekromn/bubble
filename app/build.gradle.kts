plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.mekromn.bubble"
    // Bubble v1 deliberately targets Android 16 / API 36. API 37 is not yet a
    // production target for this release line.
    //noinspection GradleDependency
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mekromn.bubble"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
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

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
        // Bubble's 2026 production contract deliberately targets Android 16 / API 36.
        // API 37 is not a v1 target yet, so suppress this single policy warning rather
        // than weakening lint globally or creating a baseline that could hide defects.
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
    // Compose 1.12 is compiled against API 37. Keep the v1/API-36 production
    // line on the stable Compose 1.11 generation until API 37 becomes an
    // explicitly validated project target.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    // Core 1.18+ moved its compile baseline above plain API 36. Core 1.17 is
    // the newest stable line whose documented baseline remains API 36.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Lifecycle 2.11 requires compileSdk 37; keep the API-36-compatible 2.10 line.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.17.0")
    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
