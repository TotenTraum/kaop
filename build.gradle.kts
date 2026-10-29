import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.publish) apply false
}

subprojects {
    group = providers.gradleProperty("buildInfo.group").get()
    version = providers.gradleProperty("buildInfo.version").get()

    // The Gradle plugin adds the published coordinates; inside this build they are the local projects.
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("ru.ttraum:kaop-runtime")).using(project(":kaop-runtime"))
            substitute(module("ru.ttraum:kaop-compiler-plugin")).using(project(":kaop-compiler-plugin"))
        }
    }

    // Maven Central publishing for every module that applies the publish plugin.
    //   ./gradlew publishToMavenLocal                  — local check, no credentials needed
    //   ./gradlew publishAndReleaseToMavenCentral      — needs credentials in ~/.gradle/gradle.properties
    plugins.withId("com.vanniktech.maven.publish") {
        val projectUrl = providers.gradleProperty("buildInfo.url").get()

        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set(providers.provider { project.description })
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

            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true))
            }
            plugins.withId("org.jetbrains.kotlin.jvm") {
                configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
            }

            publishToMavenCentral()

            // Sign only when a key is configured, so publishToMavenLocal works on any machine.
            val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            if (hasSigningKey) signAllPublications()
        }
    }
}

// The Gradle plugin is an included build: these tasks cover it together with the libraries.
val pluginBuild = gradle.includedBuild("kaop-gradle-plugin")
tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    dependsOn(subprojects.filter { it.name.startsWith("kaop-") }.map { "${it.path}:publishToMavenLocal" })
    dependsOn(pluginBuild.task(":publishToMavenLocal"))
}
tasks.register("publishAllToMavenCentral") {
    group = "publishing"
    dependsOn(subprojects.filter { it.name.startsWith("kaop-") }.map { "${it.path}:publishAndReleaseToMavenCentral" })
    dependsOn(pluginBuild.task(":publishAndReleaseToMavenCentral"))
}
