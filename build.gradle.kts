// All plugins are declared here with `apply false` so they load once on the shared build
// classpath; each module applies the ones it needs (without versions). This avoids both
// the "Kotlin plugin loaded multiple times" warning and the cross-module
// jvm-vs-android "already on the classpath" conflict.
//
// Declaring the Android plugin here only resolves its marker (no SDK needed); it is never
// applied unless :app is included, which settings.gradle.kts gates on an SDK being present.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
