plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "Koin integration for kaop: interceptors with dependencies come from Koin"

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

    sourceSets {
        commonMain.dependencies {
            api(projects.kaopRuntime)
            api(libs.koin.core)
        }
    }
}
