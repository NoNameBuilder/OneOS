pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// SESL (io.github.oneuiproject.sesl:*) and the One UI design library are on
// Maven Central: no GitHub Packages token needed (docs/app/DESIGN.md, top).
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OneOS"

include(":app")
// OneOS inside the guest: "Back to One UI" (guest/build.gradle.kts).
include(":guest")
include(":core:model")
include(":core:device")
include(":core:root")
include(":core:safety")
