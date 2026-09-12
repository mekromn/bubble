plugins { id("com.android.application") }

val geckoAbi = providers.gradleProperty("geckoAbi").getOrElse("arm64-v8a")

android {
    namespace = "com.mekromn.bubble"
    compileSdk = 37
    compileSdkMinor = 1
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.mekromn.bubble"
        minSdk = 26
        targetSdk = 36
        versionCode = 136
        versionName = "0.7.8-relay-bp-fast"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += geckoAbi }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
                arguments += "-DANDROID_PLATFORM=android-36"
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/bubble-debug.jks")
            storePassword = "bubble-debug-2026"
            keyAlias = "bubble-debug"
            keyPassword = "bubble-debug-2026"
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
        create("performance") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug" // Upgrade existing installs; this is NOT a debuggable build.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-performance.pro")
            matchingFallbacks += "release"
            externalNativeBuild.cmake.arguments += "-DBUBBLE_OPTIMIZED=ON"
        }
    }
    testBuildType = "performance"
    lint { abortOnError = true; disable += "OldTargetApi" }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview-$geckoAbi:154.0.20260824154132")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-ktx:1.19.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
