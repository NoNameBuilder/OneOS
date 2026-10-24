// Android library: read-only device fact probe (system properties, displays,
// input devices, sensors, StatFs, battery). Turns raw reads into the pure
// :core:model types.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fahadali.oneos.core.device"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    api(project(":core:model"))
}
