import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import java.util.Properties

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publish)
}

// Coordinates and version are shared with the main build.
val buildInfo = Properties().apply { file("../gradle.properties").inputStream().use(::load) }
group = buildInfo.getProperty("buildInfo.group")
version = buildInfo.getProperty("buildInfo.version")
description = "Gradle plugin for kaop: applies the compiler plugin and adds the runtime"

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("kaop") {
            id = "ru.ttraum.kaop"
            implementationClass = "ru.ttraum.kaop.gradle.KaopGradlePlugin"
            displayName = "kaop"
            description = project.description
        }
    }
}

// The plugin must reference the compiler plugin and runtime of exactly its own version.
val generateBuildInfo by tasks.registering {
    val group = project.group.toString()
    val version = project.version.toString()
    val kotlinVersion = libs.versions.kotlin.get()
    val output = layout.buildDirectory.dir("generated/buildInfo")
    inputs.property("coordinates", "$group:$version:$kotlinVersion")
    outputs.dir(output)
    doLast {
        val file = output.get().file("ru/ttraum/kaop/gradle/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package ru.ttraum.kaop.gradle
            |
            |internal object BuildInfo {
            |    const val GROUP = "$group"
            |    const val VERSION = "$version"
            |    const val KOTLIN_VERSION = "$kotlinVersion"
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }

val projectUrl = buildInfo.getProperty("buildInfo.url")
mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }
    // Keep in sync with the POM in the main build's build.gradle.kts.
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set(projectUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            url.set(projectUrl)
            connection.set("scm:git:git://${projectUrl.removePrefix("https://")}.git")
            developerConnection.set("scm:git:ssh://git@${projectUrl.removePrefix("https://")}.git")
        }
        developers {
            developer {
                id.set("ttraum")
                name.set("Azim Usmanov")
                url.set("https://github.com/ttraum/")
            }
        }
    }
}
