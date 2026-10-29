import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "Ktor DI integration for kaop: interceptors with dependencies come from the application's dependencies"

kotlin {
    jvmToolchain(17)

    jvm()
    js {
        nodejs()
    }
    macosArm64()
    linuxX64()
    linuxArm64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nonJvm") {
                withJs()
                withNative()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.kaopRuntime)
            api(libs.ktor.server.core)
            api(libs.ktor.server.di)
        }
    }

    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
