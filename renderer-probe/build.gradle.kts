plugins { id("com.android.application") version "9.3.0" }
val abi = providers.gradleProperty("geckoAbi").getOrElse("arm64-v8a")
val engineVersion = providers.gradleProperty("geckoVersion").getOrElse("154.0.20260824154132")
val engineMaven = providers.gradleProperty("geckoMaven").getOrElse("https://maven.mozilla.org/maven2/")
android {
    namespace = "com.mekromn.bubble.probe"
    compileSdk = 37
    compileSdkMinor = 1
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.mekromn.bubble.probe"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").getOrElse("1").toInt()
        versionName = "0.1.0-b${versionCode}"
        ndk { abiFilters += abi }
        externalNativeBuild { cmake { arguments += "-DANDROID_PLATFORM=android-36" } }
        buildConfigField("String", "GECKO_VERSION", "\"$engineVersion\"")
        buildConfigField("String", "GECKO_MAVEN", "\"$engineMaven\"")
        buildConfigField("String", "SOURCE_SHA", "\"${providers.environmentVariable("GITHUB_SHA").getOrElse("local-uncommitted")}\"")
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/engineAssets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/signing/bubble-debug.jks")
            storePassword = "bubble-debug-2026"
            keyAlias = "bubble-debug"
            keyPassword = "bubble-debug-2026"
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    lint { abortOnError = true; disable += setOf("OldTargetApi", "ObsoleteSdkInt") }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview-$abi:$engineVersion")
    testImplementation("junit:junit:4.13.2")
}
// Archive the exact engine bytes' identity alongside the APK; an engine change is a new comparison group.
tasks.register("engineIdentity") {
    outputs.dir(layout.buildDirectory.dir("generated/engineAssets"))
    outputs.file(layout.buildDirectory.file("engine-identity.txt"))
    inputs.property("engineVersion", engineVersion)
    inputs.property("engineRepository", engineMaven)
    inputs.property("engineAbi", abi)
    inputs.files(configurations.getByName("debugRuntimeClasspath"))
    doLast {
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val aar = artifacts.single { it.moduleVersion.id.group == "org.mozilla.geckoview" && it.extension == "aar" }.file
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { stream -> val b = ByteArray(65536); while (true) { val n = stream.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val out = layout.buildDirectory.file("engine-identity.txt").get().asFile
        out.parentFile.mkdirs()
        out.writeText("coordinate=org.mozilla.geckoview:geckoview-$abi:$engineVersion\nrepository=$engineMaven\nsha256=$hash\n")
        val asset = layout.buildDirectory.file("generated/engineAssets/engine-identity.json").get().asFile
        asset.parentFile.mkdirs()
        asset.writeText(groovy.json.JsonOutput.toJson(mapOf("coordinate" to "org.mozilla.geckoview:geckoview-$abi:$engineVersion", "repository" to engineMaven, "sha256" to hash)))
    }
}

tasks.named("preBuild") { dependsOn("engineIdentity") }
