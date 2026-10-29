package ru.ttraum.kaop.kmp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Runs unchanged on JVM, JS and Native: the same weaving works on every Kotlin backend. */
class KmpAopTest {
    private val log get() = LoggingInterceptor.log

    @BeforeTest
    fun reset() {
        log.clear()
        ProbeInterceptor.probe = {}
    }

    @Test
    fun primitivesAndResult() {
        assertEquals(5, Calculator().add(2, 3))
        assertEquals(listOf("-> ru.ttraum.kaop.kmp.Calculator.add[2, 3]", "<- add = 5"), log)
    }

    @Test
    fun earlyReturn() {
        assertEquals("negative", Calculator().sign(-1))
        assertEquals("<- sign = negative", log.last())
    }

    @Test
    fun generics() {
        assertEquals("b", Calculator().max("a", "b"))
        assertEquals(10, Calculator().max(10, 3))
    }

    @Test
    fun classLevelAdviceAndNoAdvice() {
        val repo = Repository()
        assertEquals("item-7", repo.load(7))
        assertEquals("hidden", repo.internalOnly())
        assertEquals(listOf("-> ru.ttraum.kaop.kmp.Repository.load[7]", "<- load = item-7"), log)
    }

    @Test
    fun classBasedInterceptorWithAnnotationArguments() {
        assertEquals("ok after 4", Flaky(failures = 3).call())
        assertFailsWith<IllegalStateException> { Flaky(failures = 10).call() }
    }

    @Test
    fun chainAndArgumentReplacement() {
        assertEquals("[bob:1]", register("  bob ", 1))
        assertEquals("-> register[bob, 1]", log.first())
    }

    @Test
    fun extensionFunction() {
        assertEquals("HIHI", "hi".shout(2))
    }

    @Test
    fun typedArgumentAccess() {
        // arg<Int>(0) is null for the String argument (skipped), arg<Int>(1) is checked
        assertEquals("apples x3", label("apples", 3))
        val error = assertFailsWith<IllegalArgumentException> { label("apples", 0) }
        assertEquals("count must be positive, was 0", error.message)
    }

    @Test
    fun requireArgReturnsTypedValues() {
        var seen: Triple<String, String?, Int>? = null
        ProbeInterceptor.probe = { jp ->
            seen = Triple(jp.requireArg<String>(0), jp.requireArg<String?>(1), jp.requireArg<Int>(2))
        }
        assertEquals("ann/null/2", probed("ann", null, 2))
        assertEquals(Triple("ann", null, 2), seen)
    }

    @Test
    fun requireArgFailsOnTypeMismatch() {
        ProbeInterceptor.probe = { jp -> jp.requireArg<Int>(0) }
        val error = assertFailsWith<ClassCastException> { probed("ann", null, 2) }
        assertEquals("Argument #0 'name' of probed is String, expected Int", error.message)
    }

    @Test
    fun requireArgRejectsNullForNonNullType() {
        ProbeInterceptor.probe = { jp -> jp.requireArg<String>(1) }
        val error = assertFailsWith<ClassCastException> { probed("ann", null, 2) }
        assertEquals("Argument #1 'nickname' of probed is null, expected String", error.message)
    }

    @Test
    fun exceptionsPropagateThroughTheInterceptor() {
        assertFailsWith<IllegalArgumentException> { Calculator().divide(1.0, 0.0) }
        assertEquals("!! divide threw division by zero", log.last())
    }

    @Test
    fun defaultArguments() {
        assertEquals("Hello, Bob!", Calculator().greet("Bob"))
        assertEquals("-> ru.ttraum.kaop.kmp.Calculator.greet[Bob, Hello]", log.first())
    }

    @Test
    fun nullableResult() {
        assertNull(Calculator().find("x"))
        assertEquals("<- find = null", log.last())
    }

    @Test
    fun unitFunctionKeepsSideEffects() {
        val calc = Calculator()
        calc.increment()
        calc.increment()
        assertEquals(2, calc.counter)
        assertEquals("<- increment = kotlin.Unit", log.last())
    }

    @Test
    fun localFunctionsAndInlineLambdasInsideWovenBody() {
        assertEquals(6, Calculator().sumWithLocal(listOf(1, 2, 3)))
    }

    @Test
    fun propertyGettersWithDefaultAndCustomAccessors() {
        assertEquals("a", Config().defaultGetter)
        assertEquals("b", Config().customGetter)
        assertEquals(listOf("<- <get-defaultGetter> = a", "<- <get-customGetter> = b"), log.filter { it.startsWith("<-") })
    }

    @Test
    fun interceptorMaySkipProceed() {
        val fib = Fibonacci()
        assertEquals(12586269025L, fib.fib(50))
        assertEquals(51, fib.invocations) // memoized: each n computed once
    }

    @Test
    fun earlyReturnFromSuspendFunction() = runTest {
        assertEquals("invalid", RemoteApi().fetch(-1))
        assertEquals("<- fetch = invalid", log.last())
        assertEquals("data-5", RemoteApi().fetch(5))
    }

    @Test
    fun suspendExtensionFunction() = runTest {
        assertEquals(42, 21.doubleLater())
        assertEquals("-> doubleLater[]", log.first())
    }

    @Test
    fun exceptionFromSuspendFunctionPropagates() = runTest {
        assertFailsWith<IllegalStateException> { RemoteApi().fail() }
        assertEquals("!! fail threw remote failure", log.last())
    }

    @Test
    fun suspendInterceptorCanSwitchDispatcher() = runTest {
        assertEquals("switched", RemoteApi().onDefaultDispatcher())
    }

    @Test
    fun suspendFunction() = runTest {
        assertEquals("async-3", Repository().loadAsync(3))
        assertEquals(listOf("-> ru.ttraum.kaop.kmp.Repository.loadAsync[3]", "<- loadAsync = async-3"), log)
    }

    @Test
    fun genericSuspendFunction() = runTest {
        assertEquals(42, RemoteApi().echo(42))
        assertEquals("x", RemoteApi().echo("x"))
        assertEquals("<- echo = x", log.last())
    }

    @Test
    fun suspendInterceptorCanSuspend() = runTest {
        assertEquals("done in 1", RemoteApi().slow(1))
        assertFailsWith<TimeoutCancellationException> { RemoteApi().slow(1_000) }
    }
}
