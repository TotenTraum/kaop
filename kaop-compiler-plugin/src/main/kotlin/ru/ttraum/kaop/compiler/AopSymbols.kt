package ru.ttraum.kaop.compiler

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

val RUNTIME_PACKAGE = FqName("ru.ttraum.kaop.runtime")
val ADVICE_FQ_NAME = RUNTIME_PACKAGE.child(Name.identifier("Advice"))
val NO_ADVICE_FQ_NAME = RUNTIME_PACKAGE.child(Name.identifier("NoAdvice"))
val SCOPED_INTERCEPTOR_FQ_NAME = RUNTIME_PACKAGE.child(Name.identifier("ScopedInterceptor"))

/** Interceptor interface and join point type for one flavour of function (regular or `suspend`). */
class WeavingSymbols(
    val interceptor: IrClassSymbol,
    val intercept: IrSimpleFunctionSymbol,
    val joinPoint: IrClassSymbol,
    val joinPointConstructor: IrConstructorSymbol,
    val invokeNext: IrSimpleFunctionSymbol,
)

class AopSymbols(
    val regular: WeavingSymbols,
    val suspend: WeavingSymbols,
    val advisedMethod: IrClassSymbol,
    val interceptorAt: IrSimpleFunctionSymbol,
    val scopedInterceptorAt: IrSimpleFunctionSymbol,
    val scopedInterceptorAtSuspend: IrSimpleFunctionSymbol,
    val interceptor: IrClassSymbol,
    val providedInterceptorConstructor: IrConstructorSymbol,
) {
    companion object {
        /**
         * Looks up the kaop-runtime declarations the woven code uses. Use `finderForBuiltins()` to check that the
         * runtime is on the classpath, and `finderForSource(file)` for every woven file: the latter also records
         * the lookups for incremental compilation, so a changed runtime ABI recompiles the files that call it.
         */
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun resolve(finder: DeclarationFinder): AopSymbols? {
            fun klass(name: String) =
                finder.findClass(ClassId(RUNTIME_PACKAGE, Name.identifier(name)))

            fun weaving(interceptorName: String, joinPointName: String): WeavingSymbols? {
                val interceptor = klass(interceptorName) ?: return null
                val joinPoint = klass(joinPointName) ?: return null
                return WeavingSymbols(
                    interceptor,
                    interceptor.owner.functions.single { it.name.asString() == "intercept" }.symbol,
                    joinPoint,
                    joinPoint.owner.primaryConstructor?.symbol ?: return null,
                    joinPoint.owner.functions.single { it.name.asString() == "invokeNext" }.symbol,
                )
            }

            val advisedMethod = klass("AdvisedMethod") ?: return null
            return AopSymbols(
                regular = weaving("MethodInterceptor", "JoinPoint") ?: return null,
                suspend = weaving("SuspendMethodInterceptor", "SuspendJoinPoint") ?: return null,
                advisedMethod = advisedMethod,
                interceptorAt = advisedMethod.function("interceptorAt"),
                scopedInterceptorAt = advisedMethod.function("scopedInterceptorAt"),
                scopedInterceptorAtSuspend = advisedMethod.function("scopedInterceptorAtSuspend"),
                interceptor = klass("Interceptor") ?: return null,
                providedInterceptorConstructor = klass("ProvidedInterceptor")?.owner?.primaryConstructor?.symbol ?: return null,
            )
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun IrClassSymbol.function(name: String): IrSimpleFunctionSymbol =
    owner.functions.single { it.name.asString() == name }.symbol
