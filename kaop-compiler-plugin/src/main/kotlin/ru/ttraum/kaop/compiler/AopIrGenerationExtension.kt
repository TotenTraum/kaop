package ru.ttraum.kaop.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import java.io.File

class AopIrGenerationExtension(
    private val messageCollector: MessageCollector,
    private val dumpDir: String? = null,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val symbols = AopSymbols.resolve(pluginContext.finderForBuiltins())
        if (symbols == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "AOP compiler plugin is applied, but ru.ttraum.kaop:kaop-runtime is not on the classpath; weaving skipped",
            )
            return
        }

        // Collect first, weave afterward: weaving moves bodies around and must not race the traversal.
        val functions = mutableListOf<IrSimpleFunction>()
        moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                functions += declaration
                declaration.acceptChildrenVoid(this)
            }
        })

        val weaver = AopWeaver(pluginContext, symbols, messageCollector)
        // Innermost (local) functions first, so an enclosing function moves an already-woven body.
        functions.asReversed().forEach(weaver::weaveIfAdvised)

        if (dumpDir != null) dumpKotlinLike(weaver.wovenFiles, File(dumpDir))
    }

    /** Writes each woven file as Kotlin-like source, mirroring its package path. */
    private fun dumpKotlinLike(files: Collection<IrFile>, dir: File) {
        for (file in files) {
            val out = dir
                .resolve(file.packageFqName.pathSegments().joinToString("/"))
                .resolve(File(file.fileEntry.name).name)
            out.parentFile.mkdirs()
            out.writeText(file.dumpKotlinLike())
            messageCollector.report(
                CompilerMessageSeverity.INFO,
                "AOP: woven code of ${file.fileEntry.name} dumped to $out"
            )
        }
    }
}
