plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "Runtime API for kaop: advice annotations, interceptors and join points"

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
            // withContext / ThreadLocal.asContextElement for interceptor scopes
            implementation(libs.kotlinx.coroutines.core)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("ru.ttraum.kaop.runtime.InternalKaopApi")
    }
}
