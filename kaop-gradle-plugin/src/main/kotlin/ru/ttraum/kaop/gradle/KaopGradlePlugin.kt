package ru.ttraum.kaop.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** `kaop { ... }` in a build script. */
abstract class KaopExtension {
    /** Weave advice (default `true`). */
    abstract val enabled: Property<Boolean>

    /** Write every woven file as Kotlin-like source into this directory (for inspecting generated code). */
    abstract val dumpDir: DirectoryProperty

    /** Add `ru.ttraum:kaop-runtime` of the matching version to the module (default `true`). */
    abstract val addRuntimeDependency: Property<Boolean>
}

/**
 * `plugins { id("ru.ttraum.kaop") }`: applies the kaop compiler plugin to every Kotlin compilation (JVM, JS,
 * Native) and adds the runtime of the same version, so the two can never mismatch.
 */
class KaopGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create("kaop", KaopExtension::class.java).apply {
            enabled.convention(true)
            addRuntimeDependency.convention(true)
        }
        val runtime = "${BuildInfo.GROUP}:kaop-runtime:${BuildInfo.VERSION}"
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            target.addRuntime("implementation", runtime, extension)
        }
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            target.addRuntime("commonMainImplementation", runtime, extension)
        }
    }

    private fun Project.addRuntime(configuration: String, runtime: String, extension: KaopExtension) {
        configurations.named(configuration) {
            it.dependencies.addLater(
                extension.addRuntimeDependency.filter { add -> add }.map { dependencies.create(runtime) },
            )
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "ru.ttraum.kaop"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = BuildInfo.GROUP, artifactId = "kaop-compiler-plugin", version = BuildInfo.VERSION)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val extension = project.extensions.getByType(KaopExtension::class.java)
        return project.provider {
            buildList {
                add(SubpluginOption("enabled", extension.enabled.get().toString()))
                extension.dumpDir.orNull?.let { add(SubpluginOption("dumpDir", it.asFile.absolutePath)) }
            }
        }
    }
}
