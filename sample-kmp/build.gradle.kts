plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("ru.ttraum.kaop")   // compiler plugin for JVM, JS and Native + kaop-runtime
}

kotlin {
    jvmToolchain(17)

    jvm()
    js {
        nodejs()
    }
    macosArm64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.kaopKoin)
        }
        jvmTest.dependencies {
            implementation(projects.kaopKtor)
            implementation(libs.ktor.server.test.host)
        }
    }
}

// ./gradlew :sample-kmp:build -PaopDump — verify the generated IR and write the woven code to build/aop-dump
if (project.hasProperty("aopDump")) {
    kaop {
        dumpDir.set(layout.buildDirectory.dir("aop-dump"))
    }
    kotlin.compilerOptions.freeCompilerArgs.add("-Xverify-ir=error")
}
