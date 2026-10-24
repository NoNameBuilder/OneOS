// Kotlin/JVM: safety-critical reads that need a library. HostInstalldTable
// derives One UI's installd transaction table from its framework dex files
// with smali-dexlib2 (read-only), so the same code runs on the phone and in
// the JVM tests (and later on the server).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    api(project(":core:model"))
    implementation(libs.smali.dexlib2)
    testImplementation(libs.junit)
}

// The F8U table the scripts derived (switcher/patches/host_installd.txt) and,
// when ONEOS_HOST_SERVICES_JAR points at that phone's services.jar (never
// committed), a check that HostInstalldTable reproduces it byte for byte.
val installdFixture = rootProject.layout.projectDirectory.file("../switcher/patches/host_installd.txt")
val hostServicesJar = providers.environmentVariable("ONEOS_HOST_SERVICES_JAR")

tasks.test {
    inputs.file(installdFixture).withPropertyName("installdFixture")
    systemProperty("oneos.installdFixture", installdFixture.asFile.path)
    hostServicesJar.orNull?.let { systemProperty("oneos.hostServicesJar", it) }
}
