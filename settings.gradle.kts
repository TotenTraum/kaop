rootProject.name = "kaop"

pluginManagement {
    // The kaop Gradle plugin, applied by id `ru.ttraum.kaop` in the samples.
    includeBuild("kaop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Published libraries
include(":kaop-runtime")
include(":kaop-compiler-plugin")
include(":kaop-koin")
include(":kaop-ktor")

// Examples (not published)
include(":sample-kmp")
