package ru.ttraum.kaop.runtime

import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Marks an interceptor class whose instance depends on the current unit of work (an HTTP request, a job,
 * a DI scope...). Such an interceptor is looked up in the current [InterceptorScope] on every call instead of
 * being cached per function; outside a scope it is resolved like any other class (provider, then constructor).
 * Unmarked interceptors never pay for scope lookups.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class ScopedInterceptor

/** Supplies scoped interceptors for one unit of work. Return `null` to fall back to the global resolution. */
fun interface InterceptorScope {
    fun get(type: KClass<out Interceptor>): Interceptor?
}

/**
 * Runs [block] with [scope] as the current interceptor scope. It is visible to advised `suspend` functions in
 * [block] on every platform, and on the JVM also to regular functions called from it (on any thread the
 * coroutine resumes on). On JS/Native regular functions need [inInterceptorScope].
 */
suspend fun <T> withInterceptorScope(scope: InterceptorScope, block: suspend () -> T): T =
    withContext(scopeContext(scope)) { block() }

/**
 * Runs [block] with [scope] as the current interceptor scope for advised regular functions called on this
 * thread, without coroutines. [block] must not suspend (the scope does not follow a suspended coroutine).
 */
fun <T> inInterceptorScope(scope: InterceptorScope, block: () -> T): T {
    val previous = threadScope
    threadScope = scope
    try {
        return block()
    } finally {
        threadScope = previous
    }
}

/** The interceptor scope of a coroutine context. */
class KaopScopeElement(val scope: InterceptorScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KaopScopeElement>
}

/** The coroutine context for [scope]; on the JVM it also propagates the scope into a thread-local. */
internal expect fun scopeContext(scope: InterceptorScope): CoroutineContext

/** The scope visible to regular (non-suspend) code on the current thread. */
internal expect var threadScope: InterceptorScope?
