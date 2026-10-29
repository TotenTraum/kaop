package ru.ttraum.kaop.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.builders.declarations.buildFun

/**
 * Rewrites an advised function
 *
 * ```
 * class C { @A @B fun f(a: X, b: Y): R { <body> } }      // @A -> object IA, @B -> class IB
 * ```
 *
 * into
 *
 * ```
 * class C {
 *     private static var aop$f$0: AdvisedMethod? = null   // synthetic, created on the first call
 *
 *     fun f(a: X, b: Y): R {
 *         class AopJoinPoint1(...) : JoinPoint(...) {       // the last step runs the original body
 *             override fun invokeNext(target: Any?, args: Array<Any?>): Any? = aopStep(target, args)
 *             fun aopStep(target: Any?, args: Array<Any?>): Any? {
 *                 val self = target as C
 *                 val a = args[0] as X
 *                 val b = args[1] as Y
 *                 <body, with this -> self, a -> a', b -> b', `return@f` -> return from aopStep>
 *             }
 *         }
 *         class AopJoinPoint0(...) : JoinPoint(...) {       // every other step calls the next interceptor
 *             override fun invokeNext(target: Any?, args: Array<Any?>): Any? = aopStep(target, args)
 *             fun aopStep(target: Any?, args: Array<Any?>): Any? =
 *                 (aop$f$0!!.interceptorAt(1) as IB).intercept(AopJoinPoint1(aop$f$0!!, target, args, 1))
 *         }
 *         val advised = aop$f$0 ?: AdvisedMethod("C", "f", arrayOf("a", "b"), "R", false,
 *             arrayOf(IA, IB()), arrayOf(A(), B())).also { aop$f$0 = it }
 *         return IA.intercept(AopJoinPoint0(advised, this, arrayOf<Any?>(a, b), 0)) as R
 *     }
 * }
 * ```
 *
 * Why it is shaped like this (performance):
 * - metadata is cached per function; the generated classes capture nothing (receiver and arguments are passed
 *   through the join point), so a call allocates only the argument array and one join point per interceptor;
 * - every interceptor call and every `proceed()` dispatches on an object whose exact class is created right
 *   there, and interceptors are called through their concrete class. No call site is shared between advised
 *   functions, so even in a large application (megamorphic profiles) the JIT can inline the interceptor,
 *   `proceed()` and the body into `f`, and escape analysis then removes the join point, array and boxes.
 * The cache is initialised with a benign race: two threads may both build it, and either copy is valid.
 *
 * For `suspend` functions the same shape is used with `SuspendJoinPoint` and a `suspend fun invokeNext`,
 * so interceptors can suspend around the call.
 *
 * The body stays inside `f` (in a local class), so type parameters and captured locals keep working without
 * remapping — only the receiver, value parameters and returns change.
 */
private val AOP_CACHE_ORIGIN =
    IrDeclarationOriginImpl("AOP_ADVISED_METHOD_CACHE", isSynthetic = true)
private val AOP_JOIN_POINT_ORIGIN = IrDeclarationOriginImpl("AOP_JOIN_POINT", isSynthetic = true)

@OptIn(UnsafeDuringIrConstructionAPI::class)
class AopWeaver(
    private val context: IrPluginContext,
    private val symbols: AopSymbols,
    private val messageCollector: MessageCollector,
) {
    private val builtIns = context.irBuiltIns
    private val arrayGet =
        builtIns.arrayClass.owner.functions.single { it.name.asString() == "get" }
    private val arrayOfNullableAny = builtIns.arrayClass.typeWith(builtIns.anyNType)
    private val typeSystem = IrTypeSystemContextImpl(builtIns)

    /** Files that contain at least one woven function. */
    val wovenFiles = linkedSetOf<IrFile>()

    private class Binding(val annotation: IrConstructorCall, val interceptor: IrClassSymbol)

    fun weaveIfAdvised(function: IrSimpleFunction) {
        // Accessors of `val x = ...` are generated (DEFAULT_PROPERTY_ACCESSOR) but may carry `@get:` advice.
        val userDeclared = function.origin == IrDeclarationOrigin.DEFINED ||
            function.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
        if (!userDeclared || function.isFakeOverride) return
        val bindings = collectBindings(function)
        if (bindings.isEmpty()) return

        val problem = when {
            function.body == null -> return warnIfAbstract(function) // abstract / external: nothing to weave
            function.isInline -> "inline functions are not supported"
            function.isTailrec -> "tailrec functions are not supported"
            else -> null
        }
        if (problem != null) return report(
            function,
            CompilerMessageSeverity.ERROR,
            "AOP: ${function.name}: $problem"
        )

        val weaving = weavingSymbols(function)
        for (binding in bindings) {
            val owner = binding.interceptor.owner
            if (!owner.isSubclassOf(weaving.interceptor.owner)) {
                return report(
                    function, CompilerMessageSeverity.ERROR,
                    "AOP: ${function.name}: interceptor ${owner.kotlinFqName} must implement " +
                            "${weaving.interceptor.owner.kotlinFqName} to advise ${if (function.isSuspend) "suspend" else "regular"} functions",
                )
            }
        }

        weave(function, bindings, weaving)
    }

    private fun weavingSymbols(function: IrSimpleFunction): WeavingSymbols =
        if (function.isSuspend) symbols.suspend else symbols.regular

    /**
     * Advice written directly on an abstract function (typically an interface method) would silently do nothing:
     * there is no body to weave, and overrides do not inherit it. Class-level advice is not reported — on an
     * interface it still applies to default methods.
     */
    private fun warnIfAbstract(function: IrSimpleFunction) {
        if (function.modality != Modality.ABSTRACT || function.isExpect || function.isExternal) return
        val owner = function.correspondingPropertySymbol?.owner?.name ?: function.name
        val where = function.parentClassOrNull?.let { "${it.name}.$owner" } ?: owner.asString()
        for (binding in function.annotations.mapNotNull(::bindingOf)) {
            report(
                function, CompilerMessageSeverity.WARNING,
                "AOP: @${binding.annotation.symbol.owner.constructedClass.name} on $where has no effect: it has no body, " +
                    "and advice is not inherited by overrides. Put the annotation on the implementation.",
            )
        }
    }

    /** Class-level advice first (outermost), then function-level advice, in declaration order. */
    private fun collectBindings(function: IrSimpleFunction): List<Binding> {
        if (function.hasAnnotation(NO_ADVICE_FQ_NAME)) return emptyList()
        val fromClass = if (function.correspondingPropertySymbol == null) {
            function.parentClassOrNull?.annotations?.mapNotNull(::bindingOf).orEmpty()
        } else {
            emptyList()
        }
        return fromClass + function.annotations.mapNotNull(::bindingOf)
    }

    private fun bindingOf(annotation: IrConstructorCall): Binding? {
        val annotationClass = annotation.symbol.owner.constructedClass
        val advice = annotationClass.getAnnotation(ADVICE_FQ_NAME) ?: return null
        val interceptorRef = advice.arguments[0] as? IrClassReference ?: return null
        val interceptor = interceptorRef.classType.classOrNull ?: return null
        return Binding(annotation, interceptor)
    }

    private fun weave(
        function: IrSimpleFunction,
        bindings: List<Binding>,
        weaving: WeavingSymbols
    ) {
        if (wovenFiles.add(function.file)) {
            // Record that this file depends on the runtime classes its woven code calls (incremental compilation).
            AopSymbols.resolve(context.finderForSource(function.file))
        }
        val regularParams = function.parameters.filter { it.kind == IrParameterKind.Regular }
        val receiver = function.dispatchReceiverParameter
            ?: function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }

        val oldStatements = when (val body = function.body) {
            is IrBlockBody -> body.statements.toList()
            is IrExpressionBody -> listOf(
                IrReturnImpl(
                    body.startOffset,
                    body.endOffset,
                    builtIns.nothingType,
                    function.symbol,
                    body.expression
                ),
            )

            else -> return
        }

        val cache = createCacheField(function)
        val advisedType = symbols.advisedMethod.defaultType

        // Built from the last step to the first: step k's invokeNext instantiates step k+1.
        val steps = arrayOfNulls<IrClass>(bindings.size)
        for (index in bindings.indices.reversed()) {
            steps[index] = buildJoinPointClass(
                function,
                weaving,
                index
            ) { invokeNext, targetParam, argsParam ->
                if (index == bindings.lastIndex) {
                    moveBody(
                        function,
                        invokeNext,
                        targetParam,
                        argsParam,
                        receiver,
                        regularParams,
                        oldStatements
                    )
                } else {
                    +irReturn(
                        interceptorCall(
                            bindings, index + 1, steps[index + 1]!!,
                            advised = { irImplicitCast(irGetField(null, cache), advisedType) },
                            target = irGet(targetParam),
                            args = irGet(argsParam),
                        ),
                    )
                }
            }
        }

        val builder =
            DeclarationIrBuilder(context, function.symbol, function.startOffset, function.endOffset)
        function.body = builder.irBlockBody {
            steps.forEach { +it!! }
            val advised = irTemporary(
                irBlock(resultType = advisedType) {
                    val cached = irTemporary(irGetField(null, cache))
                    +irIfNull(
                        advisedType,
                        irGet(cached),
                        irBlock(resultType = advisedType) {
                            val created =
                                irTemporary(newAdvisedMethod(function, bindings, regularParams))
                            +irSetField(null, cache, irGet(created))
                            +irGet(created)
                        },
                        irImplicitCast(irGet(cached), advisedType),
                    )
                },
                nameHint = "advised",
            )
            val call = interceptorCall(
                bindings, 0, steps[0]!!,
                advised = { irGet(advised) },
                target = receiver?.let { irGet(it) } ?: irNull(),
                args = irArrayOf(builtIns.anyNType, regularParams.map { irGet(it) }),
            )
            if (function.returnType.isUnit()) {
                +call
            } else {
                +irReturn(irAs(call, function.returnType))
            }
        }
        function.body!!.patchDeclarationParents(function)
    }

    /** `I_k.intercept(Step_k(advised, target, args, k))`, calling the interceptor through its concrete class. */
    private fun IrBuilderWithScope.interceptorCall(
        bindings: List<Binding>,
        index: Int,
        step: IrClass,
        advised: () -> IrExpression,
        target: IrExpression,
        args: IrExpression,
    ): IrExpression {
        val weaving = weavingSymbols(step)
        val joinPoint = irCallConstructor(step.primaryConstructor!!.symbol, emptyList()).apply {
            arguments[0] = advised()
            arguments[1] = target
            arguments[2] = args
            arguments[3] = irInt(index)
        }
        val interceptor = bindings[index].interceptor.owner
        val instance = if (interceptor.kind == ClassKind.OBJECT) {
            irGetObject(interceptor.symbol)
        } else {
            // @ScopedInterceptor classes are looked up in the current scope on every call; others are cached.
            val lookup = when {
                !interceptor.hasAnnotation(SCOPED_INTERCEPTOR_FQ_NAME) -> symbols.interceptorAt
                weaving === symbols.suspend -> symbols.scopedInterceptorAtSuspend
                else -> symbols.scopedInterceptorAt
            }
            irAs(
                irCall(lookup).apply {
                    arguments[0] = advised()
                    arguments[1] = irInt(index)
                },
                interceptor.defaultType,
            )
        }
        val intercept = interceptor.functions.firstOrNull { candidate ->
            candidate.name == weaving.intercept.owner.name &&
                    (candidate.symbol == weaving.intercept || candidate.allOverridden()
                        .any { it.symbol == weaving.intercept })
        }?.symbol ?: weaving.intercept
        return irCall(intercept, builtIns.anyNType).apply {
            arguments[0] = instance
            arguments[1] = joinPoint
        }
    }

    private fun weavingSymbols(step: IrClass): WeavingSymbols =
        if (step.superTypes.single().classOrNull == symbols.suspend.joinPoint) symbols.suspend else symbols.regular

    /**
     * `class AopJoinPoint<index>(advised, target, args, index) : JoinPoint(...) { override fun invokeNext(...) }`,
     * declared locally in [function].
     */
    private fun buildJoinPointClass(
        function: IrSimpleFunction,
        weaving: WeavingSymbols,
        index: Int,
        invokeNextBody: IrBlockBodyBuilder.(step: IrSimpleFunction, target: IrValueParameter, args: IrValueParameter) -> Unit,
    ): IrClass {
        val step = context.irFactory.buildClass {
            startOffset = function.startOffset
            endOffset = function.endOffset
            name = Name.identifier("AopJoinPoint$index")
            kind = ClassKind.CLASS
            visibility = DescriptorVisibilities.LOCAL
            modality = Modality.FINAL
            origin = AOP_JOIN_POINT_ORIGIN
        }
        step.parent = function
        step.createThisReceiverParameter()
        step.superTypes = listOf(weaving.joinPoint.defaultType)

        val superConstructor = weaving.joinPointConstructor.owner
        step.addConstructor {
            startOffset = function.startOffset
            endOffset = function.endOffset
            isPrimary = true
            visibility = DescriptorVisibilities.PUBLIC
            origin = AOP_JOIN_POINT_ORIGIN
        }.also { constructor ->
            val params = superConstructor.nonDispatchParameters.map {
                constructor.addValueParameter(
                    it.name,
                    it.type
                )
            }
            constructor.body = DeclarationIrBuilder(
                context,
                constructor.symbol,
                function.startOffset,
                function.endOffset
            ).irBlockBody {
                +irDelegatingConstructorCall(superConstructor).apply {
                    params.forEachIndexed { i, param -> arguments[i] = irGet(param) }
                }
                +IrInstanceInitializerCallImpl(
                    startOffset,
                    endOffset,
                    step.symbol,
                    builtIns.unitType
                )
            }
        }

        // The actual work goes into a separate method; invokeNext only forwards to it. invokeNext is called from the
        // shared JoinPoint.proceed(), whose profile is polluted in a large app, so C2 only inlines it when it is
        // tiny (MaxInlineSize = 35 bytes). The private method's call site belongs to this class alone, stays hot and
        // is inlined under the much larger FreqInlineSize.
        val superInvokeNext = weaving.invokeNext.owner
        // Not private: a private suspend aopStep would be reached from its continuation class through a synthetic
        // accessor, which Kotlin 2.4 fails to generate for local classes inside generic functions. The class is
        // local and final, so a public member is not reachable from outside anyway.
        val work = step.addMemberFunction(
            function,
            Name.identifier("aopStep"),
            DescriptorVisibilities.PUBLIC,
            superInvokeNext.isSuspend
        )
        val workTarget = work.addValueParameter("target", builtIns.anyNType)
        val workArgs = work.addValueParameter("args", arrayOfNullableAny)
        work.body = DeclarationIrBuilder(
            context,
            work.symbol,
            function.startOffset,
            function.endOffset
        ).irBlockBody {
            invokeNextBody(work, workTarget, workArgs)
        }

        val invokeNext = step.addMemberFunction(
            function,
            superInvokeNext.name,
            superInvokeNext.visibility,
            superInvokeNext.isSuspend
        )
        invokeNext.overriddenSymbols = listOf(superInvokeNext.symbol)
        val targetParam = invokeNext.addValueParameter("target", builtIns.anyNType)
        val argsParam = invokeNext.addValueParameter("args", arrayOfNullableAny)
        invokeNext.body = DeclarationIrBuilder(
            context,
            invokeNext.symbol,
            function.startOffset,
            function.endOffset
        ).irBlockBody {
            +irReturn(
                irCall(work.symbol).apply {
                    arguments[0] = irGet(invokeNext.dispatchReceiverParameter!!)
                    arguments[1] = irGet(targetParam)
                    arguments[2] = irGet(argsParam)
                },
            )
        }

        step.addFakeOverrides(typeSystem)
        return step
    }

    private fun IrClass.addMemberFunction(
        function: IrSimpleFunction,
        name: Name,
        visibility: DescriptorVisibility,
        isSuspend: Boolean,
    ): IrSimpleFunction {
        val member = addFunction {
            startOffset = function.startOffset
            endOffset = function.endOffset
            this.name = name
            returnType = builtIns.anyNType
            modality = Modality.FINAL
            this.visibility = visibility
            this.isSuspend = isSuspend
            origin = AOP_JOIN_POINT_ORIGIN
        }
        if (member.dispatchReceiverParameter == null) {
            member.parameters = listOf(
                thisReceiver!!.copyTo(
                    member,
                    kind = IrParameterKind.DispatchReceiver
                )
            ) + member.parameters
        }
        return member
    }

    /** Moves the original body into [into], unpacking the receiver and the (possibly replaced) arguments. */
    private fun IrBlockBodyBuilder.moveBody(
        function: IrSimpleFunction,
        into: IrSimpleFunction,
        targetParam: IrValueParameter,
        argsParam: IrValueParameter,
        receiver: IrValueParameter?,
        regularParams: List<IrValueParameter>,
        oldStatements: List<IrStatement>,
    ) {
        val remapped = mutableMapOf<IrValueParameterSymbol, IrVariable>()
        if (receiver != null) {
            remapped[receiver.symbol] =
                irTemporary(irAs(irGet(targetParam), receiver.type), nameHint = "self")
        }
        regularParams.forEachIndexed { index, param ->
            val element = irCall(arrayGet.symbol, builtIns.anyNType).apply {
                arguments[0] = irGet(argsParam)
                arguments[1] = irInt(index)
            }
            remapped[param.symbol] =
                irTemporary(irAs(element, param.type), nameHint = param.name.asString())
        }
        val relocator = BodyRelocator(function, into, remapped)
        for (statement in oldStatements) {
            +(statement.transform(relocator, null) as IrStatement)
        }
        +irReturn(irUnit())
    }

    private fun IrBuilderWithScope.newAdvisedMethod(
        function: IrSimpleFunction,
        bindings: List<Binding>,
        regularParams: List<IrValueParameter>,
    ): IrExpression {
        val constructor = symbols.advisedMethod.owner.primaryConstructor!!.symbol
        return irCallConstructor(constructor, emptyList()).apply {
            arguments[0] =
                function.parentClassOrNull?.kotlinFqName?.asString()?.let(::irString) ?: irNull()
            arguments[1] = irString(function.name.asString())
            arguments[2] =
                irArrayOf(builtIns.stringType, regularParams.map { irString(it.name.asString()) })
            arguments[3] = irString(function.returnType.describe())
            arguments[4] = irBoolean(function.isSuspend)
            arguments[5] = irArrayOf(
                builtIns.anyType,
                bindings.map { interceptorRef(function, it.interceptor) })
            arguments[6] = irArrayOf(
                builtIns.annotationType,
                bindings.map { it.annotation.deepCopyWithSymbols(function) })
        }
    }

    private val cacheFieldCounters = HashMap<IrDeclarationContainer, Int>()

    /**
     * A private static field next to the function: in its class, or at file level when the class
     * cannot hold a mutable static field (interfaces, value classes).
     */
    private fun createCacheField(function: IrSimpleFunction): IrField {
        val owner =
            generateSequence(function.parent) { (it as? IrDeclaration)?.parent }.firstOrNull { it is IrClass || it is IrFile }
        val container: IrDeclarationContainer =
            if (owner is IrClass && owner.kind != ClassKind.INTERFACE && !owner.isValue) owner else function.file
        val index = cacheFieldCounters.merge(container, 1, Int::plus)!! - 1
        val safeName =
            function.name.asString().map { if (it.isLetterOrDigit() || it == '_') it else '_' }
                .joinToString("")
        val field = context.irFactory.buildField {
            startOffset = function.startOffset
            endOffset = function.endOffset
            name = Name.identifier($$"aop$$$safeName$$$index")
            type = symbols.advisedMethod.defaultType.makeNullable()
            visibility = DescriptorVisibilities.PRIVATE
            origin = AOP_CACHE_ORIGIN
            isStatic = true
            isFinal = false
        }
        field.parent = container
        container.declarations += field
        return field
    }

    /** Redirects receiver/parameter reads to the unpacked locals and `return@function` to [into]. */
    private class BodyRelocator(
        private val function: IrSimpleFunction,
        private val into: IrSimpleFunction,
        private val params: Map<IrValueParameterSymbol, IrVariable>,
    ) : IrTransformer<Nothing?>() {
        override fun visitGetValue(expression: IrGetValue, data: Nothing?): IrExpression {
            val replacement =
                params[expression.symbol] ?: return super.visitGetValue(expression, data)
            return IrGetValueImpl(
                expression.startOffset,
                expression.endOffset,
                replacement.symbol,
                expression.origin
            )
        }

        override fun visitReturn(expression: IrReturn, data: Nothing?): IrExpression {
            expression.transformChildren(this, data)
            if (expression.returnTargetSymbol == function.symbol) {
                expression.returnTargetSymbol = into.symbol
            }
            return expression
        }
    }

    /**
     * An `object` interceptor is passed as is. A class becomes `ProvidedInterceptor(C::class, { C() })`: it is
     * resolved through the installed InterceptorProvider (a DI container), falling back to a constructor that
     * needs no arguments — or to `null` when there is none (abstract class, interface, constructor parameters).
     */
    private fun IrBuilderWithScope.interceptorRef(function: IrSimpleFunction, interceptor: IrClassSymbol): IrExpression {
        val owner = interceptor.owner
        if (owner.kind == ClassKind.OBJECT) return irGetObject(interceptor)

        val type = interceptor.starProjectedType
        val noArgConstructor = owner.constructors.firstOrNull { constructor ->
            constructor.nonDispatchParameters.all { it.defaultValue != null }
        }?.takeIf { owner.modality != Modality.ABSTRACT && owner.modality != Modality.SEALED && owner.kind == ClassKind.CLASS }

        return irCallConstructor(symbols.providedInterceptorConstructor, emptyList()).apply {
            arguments[0] = IrClassReferenceImpl(startOffset, endOffset, builtIns.kClassClass.typeWith(type), interceptor, type)
            arguments[1] = if (noArgConstructor == null) irNull() else factoryLambda(function, noArgConstructor)
        }
    }

    /** `{ C() }` as a `() -> Interceptor`; default values fill in constructor parameters. */
    private fun IrBuilderWithScope.factoryLambda(function: IrSimpleFunction, constructor: IrConstructor): IrExpression {
        val lambda = context.irFactory.buildFun {
            startOffset = function.startOffset
            endOffset = function.endOffset
            name = SpecialNames.ANONYMOUS
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            visibility = DescriptorVisibilities.LOCAL
            returnType = symbols.interceptor.defaultType
        }
        lambda.parent = function
        lambda.body = DeclarationIrBuilder(context, lambda.symbol, function.startOffset, function.endOffset).irBlockBody {
            +irReturn(irCallConstructor(constructor.symbol, emptyList()))
        }
        return IrFunctionExpressionImpl(
            startOffset, endOffset,
            builtIns.functionN(0).typeWith(symbols.interceptor.defaultType),
            lambda,
            IrStatementOrigin.LAMBDA,
        )
    }

    private fun IrBuilderWithScope.irArrayOf(
        elementType: IrType,
        elements: List<IrExpression>
    ): IrExpression =
        irCall(
            builtIns.arrayOf,
            builtIns.arrayClass.typeWith(elementType)
        ).apply {
            typeArguments[0] = elementType
            arguments[0] = IrVarargImpl(
                startOffset, endOffset,
                builtIns.arrayClass.typeWith(elementType),
                elementType,
                elements,
            )
        }

    private fun IrType.describe(): String {
        val base = classFqName
            ?.asString()
            ?: (classifierOrNull?.owner as? IrTypeParameter)?.name?.asString() ?: "?"
        return if (isMarkedNullable()) "$base?" else base
    }

    private fun report(
        function: IrSimpleFunction,
        severity: CompilerMessageSeverity,
        message: String
    ) {
        val file = function.fileOrNull
        val location = file?.let {
            CompilerMessageLocation.create(
                it.fileEntry.name,
                it.fileEntry.getLineNumber(function.startOffset) + 1,
                it.fileEntry.getColumnNumber(function.startOffset) + 1,
                null,
            )
        }
        messageCollector.report(severity, message, location)
    }
}
