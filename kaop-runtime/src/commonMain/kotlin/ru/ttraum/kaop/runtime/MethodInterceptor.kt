package ru.ttraum.kaop.runtime

/** Common supertype of interceptors that can be bound with [Advice]. */
interface Interceptor

/** "Around" advice for regular functions. Call [JoinPoint.proceed] to run the next interceptor or the original body. */
fun interface MethodInterceptor : Interceptor {
    fun intercept(joinPoint: JoinPoint): Any?
}

/**
 * "Around" advice for `suspend` functions. [intercept] may suspend (e.g. `delay`, `withContext`, `withTimeout`)
 * and calls [SuspendJoinPoint.proceed] to continue the chain.
 *
 * An interceptor may implement both [MethodInterceptor] and [SuspendMethodInterceptor] to advise both kinds.
 */
fun interface SuspendMethodInterceptor : Interceptor {
    suspend fun intercept(joinPoint: SuspendJoinPoint): Any?
}
