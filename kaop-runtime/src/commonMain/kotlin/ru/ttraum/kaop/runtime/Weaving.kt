package ru.ttraum.kaop.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Everything about an advised function that does not change between calls. The compiler plugin creates
 * one instance per advised function, lazily on its first call, and caches it in a static field.
 * **Not intended to be used by hand.**
 *
 * @param interceptors per interceptor of the chain: the instance itself for an `object`, a
 *   [ProvidedInterceptor] for a class.
 */
@InternalKaopApi
class AdvisedMethod(
    declaringClass: String?,
    methodName: String,
    parameterNames: Array<String>,
    returnType: String,
    isSuspend: Boolean,
    private val interceptors: Array<Any>,
    internal val annotations: Array<Annotation>,
) {
    val signature = MethodSignature(declaringClass, methodName, parameterNames.asList(), returnType, isSuspend)

    private val resolved = arrayOfNulls<Resolved>(interceptors.size)

    /**
     * The instance of the class-based interceptor at [index] (woven code calls it on every advised call).
     *
     * Kept tiny on purpose: C2 inlines it even from a polluted call site (MaxInlineSize = 35 bytecodes);
     * the provider lookup lives in [resolve], which runs once per function and installation.
     */
    fun interceptorAt(index: Int): Interceptor {
        val cached = resolved[index]
        if (cached != null && cached.installation === installation) return cached.instance
        return resolve(index)
    }

    /**
     * The instance of the [ScopedInterceptor] at [index] for a regular function: from the scope of the current
     * thread (see [inInterceptorScope]; on the JVM also [withInterceptorScope]), else resolved as usual.
     */
    fun scopedInterceptorAt(index: Int): Interceptor = fromScope(index, threadScope) ?: interceptorAt(index)

    /** The same for a `suspend` function: the coroutine's scope comes first, then the thread's. */
    suspend fun scopedInterceptorAtSuspend(index: Int): Interceptor =
        fromScope(index, currentCoroutineContext()[KaopScopeElement]?.scope ?: threadScope) ?: interceptorAt(index)

    private fun fromScope(index: Int, scope: InterceptorScope?): Interceptor? {
        if (scope == null) return null
        val ref = interceptors[index] as ProvidedInterceptor
        val instance = scope.get(ref.type) ?: return null
        check(ref.type.isInstance(instance)) {
            "InterceptorScope returned ${instance::class.simpleName} for ${ref.type.simpleName}"
        }
        return instance
    }

    private fun resolve(index: Int): Interceptor {
        val current = installation
        val instance = when (val ref = interceptors[index]) {
            is ProvidedInterceptor -> provide(ref, current)
            else -> ref as Interceptor
        }
        // A plain store is enough: Resolved is immutable, and a thread that misses it just resolves again.
        resolved[index] = Resolved(current, instance)
        return instance
    }

    private fun provide(ref: ProvidedInterceptor, current: Installation): Interceptor {
        val provided = current.provider?.get(ref.type)
        if (provided != null) {
            check(ref.type.isInstance(provided)) {
                "InterceptorProvider returned ${provided::class.simpleName} for ${ref.type.simpleName}"
            }
            return provided
        }
        val fallback = ref.fallback ?: throw IllegalStateException(
            "Interceptor ${ref.type.simpleName} (used by ${signature.qualifiedName}) is not provided: " +
                if (current.provider == null) {
                    "install an InterceptorProvider (Kaop.install, kaop-koin), or give it a no-arg constructor"
                } else {
                    "register it in your DI container, or give it a no-arg constructor"
                },
        )
        return fallback()
    }

    private class Resolved(val installation: Installation, val instance: Interceptor)
}
