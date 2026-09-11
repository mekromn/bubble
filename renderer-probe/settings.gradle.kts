pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("geckoMaven").getOrElse("https://maven.mozilla.org/maven2/")) } }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
}
rootProject.name = "BubbleRendererProbe"
