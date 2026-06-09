pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sneedroid"

// :core is a pure-JVM module and always builds (no Android SDK required).
include(":core")

// :app is the Android (Compose) module. It can only be configured when an Android
// SDK is available, so we include it conditionally — the core build stays green on
// machines without the SDK. Set ANDROID_HOME / ANDROID_SDK_ROOT, or add
// `sdk.dir=/path/to/sdk` to local.properties, to enable it.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    println("[sneedroid] Android SDK not found — :app excluded. Install the SDK and set " +
        "ANDROID_HOME (or local.properties sdk.dir) to build the Android app.")
}
