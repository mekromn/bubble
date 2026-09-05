plugins { id("com.android.application") }
android {
    namespace = "com.mekromn.bubble"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "com.mekromn.bubble"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    // Preserve the existing public TEST identity. Never use this for a production package.
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
    }
    lint { abortOnError = true; disable += "OldTargetApi" }
}
dependencies {
    val abi = providers.gradleProperty("geckoAbi").getOrElse("arm64-v8a")
    implementation("org.mozilla.geckoview:geckoview-$abi:154.0.20260824154132")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-ktx:1.19.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
