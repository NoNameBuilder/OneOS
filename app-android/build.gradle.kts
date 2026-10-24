// One Kotlin for every module (docs/app/DESIGN.md section 10): declaring the
// Kotlin JVM plugin here puts KGP 2.4.21 on the shared build classpath, so
// AGP's built-in Kotlin compiles the Android modules with the same 2.4.21.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
