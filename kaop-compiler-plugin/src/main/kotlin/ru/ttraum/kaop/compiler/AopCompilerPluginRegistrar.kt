package ru.ttraum.kaop.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

const val AOP_PLUGIN_ID = "ru.ttraum.kaop"

object AopConfigurationKeys {
    val ENABLED = CompilerConfigurationKey<Boolean>("enable AOP weaving")
    val DUMP_DIR =
        CompilerConfigurationKey<String>("directory for Kotlin-like dumps of woven files")
}

class AopCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = AOP_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("enabled", "<true|false>", "Whether AOP weaving is enabled", required = false),
        CliOption(
            "dumpDir",
            "<path>",
            "Write woven files as Kotlin-like source into this directory",
            required = false
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(AopConfigurationKeys.ENABLED, value.toBoolean())
            "dumpDir" -> configuration.put(AopConfigurationKeys.DUMP_DIR, value)
            else -> error("Unexpected option: ${option.optionName}")
        }
    }
}

class AopCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = AOP_PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration[AopConfigurationKeys.ENABLED] == false) return
        val messageCollector =
            configuration[CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE]
        IrGenerationExtension.registerExtension(
            AopIrGenerationExtension(
                messageCollector,
                configuration[AopConfigurationKeys.DUMP_DIR]
            )
        )
    }
}
