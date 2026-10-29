plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
}

description = "Kotlin compiler plugin that weaves kaop advice into annotated functions"

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        // Plain MessageCollector reporting (kept for precise file:line:column diagnostics).
        optIn.add("org.jetbrains.kotlin.config.MessageCollectorAccess")
    }
}
