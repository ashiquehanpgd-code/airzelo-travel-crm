pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "AirZeloAndroid"
include(":engine")
// The Android app needs the Android SDK; the engine (all business logic and its tests) builds without it.
if (System.getenv("AZ_ENGINE_ONLY") == null) include(":app")
