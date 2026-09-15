// Top-level build file. Plugin versions are pinned in gradle/libs.versions.toml so the
// AGP / Kotlin / KSP / Room combination stays mutually compatible.
//
// There is deliberately NO `org.jetbrains.kotlin.android` here. AGP 9 ships Kotlin
// support built in and treats applying that plugin as a fatal error:
//   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
//    support since AGP 9.0."
// The `kotlin { }` extension is still available - AGP registers it - so compiler
// options live there as before. android/nowinandroid does the same on this exact
// AGP 9.3.2 / Kotlin 2.3.0 pair: it applies only `com.android.application`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
