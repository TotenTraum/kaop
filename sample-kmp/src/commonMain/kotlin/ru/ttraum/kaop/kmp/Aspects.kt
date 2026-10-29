package ru.ttraum.kaop.kmp

import ru.ttraum.kaop.runtime.AbstractJoinPoint
import ru.ttraum.kaop.runtime.Advice
import ru.ttraum.kaop.runtime.JoinPoint
import ru.ttraum.kaop.runtime.MethodInterceptor
import ru.ttraum.kaop.runtime.SuspendJoinPoint
import ru.ttraum.kaop.runtime.SuspendMethodInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

@Advice(LoggingInterceptor::class)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY_GETTER)
annotation class Logged

object LoggingInterceptor : MethodInterceptor, SuspendMethodInterceptor {
    val log = mutableListOf<String>()

    override fun intercept(joinPoint: JoinPoint): Any? = logged(joinPoint) { joinPoint.proceed() }

    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? = logged(joinPoint) { joinPoint.proceed() }

    private inline fun logged(joinPoint: AbstractJoinPoint, proceed: () -> Any?): Any? {
        log += "-> ${joinPoint.method.qualifiedName}${joinPoint.args}"
        return try {
            proceed().also { log += "<- ${joinPoint.method.name} = $it" }
        } catch (e: Throwable) {
            log += "!! ${joinPoint.method.name} threw ${e.message}"
            throw e
        }
    }
}

/** Class-based interceptor reading an annotation argument. */
@Advice(RetryInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(val times: Int = 3)

class RetryInterceptor : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? {
        var last: Throwable? = null
        repeat(joinPoint.annotation<Retry>().times) {
            try {
                return joinPoint.proceed()
            } catch (e: Exception) {
                last = e
            }
        }
        throw last!!
    }
}

/** Replaces arguments before they reach the function. */
@Advice(TrimInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class TrimStrings

object TrimInterceptor : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? =
        joinPoint.proceed(*joinPoint.args.map { if (it is String) it.trim() else it }.toTypedArray())
}

@Advice(TimeoutInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Timeout(val millis: Long)

object TimeoutInterceptor : SuspendMethodInterceptor {
    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? =
        withTimeout(joinPoint.annotation<Timeout>().millis.milliseconds) { joinPoint.proceed() }
}

/** Rejects non-positive `Int` arguments; arguments of other types are skipped thanks to `arg<Int>` (`as? Int`). */
@Advice(PositiveIntsInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class PositiveInts

object PositiveIntsInterceptor : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? {
        for (i in 0 until joinPoint.argCount) {
            val value = joinPoint.arg<Int>(i) ?: continue
            require(value > 0) { "${joinPoint.method.parameterNames[i]} must be positive, was $value" }
        }
        return joinPoint.proceed()
    }
}

/** Lets tests inspect the join point of [probed] calls. */
@Advice(ProbeInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Probed

object ProbeInterceptor : MethodInterceptor {
    var probe: (JoinPoint) -> Unit = {}

    override fun intercept(joinPoint: JoinPoint): Any? {
        probe(joinPoint)
        return joinPoint.proceed()
    }
}

/** Memoizes results by arguments; does not call proceed() on a cache hit. */
@Advice(CacheInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Cached

object CacheInterceptor : MethodInterceptor {
    private val cache = HashMap<Pair<String, List<Any?>>, Any?>()

    override fun intercept(joinPoint: JoinPoint): Any? =
        cache.getOrPut(joinPoint.method.qualifiedName to joinPoint.args) { joinPoint.proceed() }
}

/** Suspend-only advice that moves the body to another dispatcher. */
@Advice(OnDefaultDispatcherInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class OnDefaultDispatcher

object OnDefaultDispatcherInterceptor : SuspendMethodInterceptor {
    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? =
        withContext(Dispatchers.Default) { joinPoint.proceed() }
}
