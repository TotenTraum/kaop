package ru.ttraum.kaop.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import ru.ttraum.kaop.runtime.Interceptor
import ru.ttraum.kaop.runtime.InterceptorScope
import ru.ttraum.kaop.runtime.withInterceptorScope
import kotlin.reflect.KClass

class KaopCallScopeConfig {
    @PublishedApi
    internal val factories = mutableMapOf<KClass<out Interceptor>, (ApplicationCall) -> Interceptor>()

    /** Creates the `@ScopedInterceptor` [T] for a call; at most once per call. */
    inline fun <reified T : Interceptor> interceptor(noinline factory: (ApplicationCall) -> T) {
        factories[T::class] = factory
    }
}

/**
 * Makes every HTTP call an interceptor scope: `@ScopedInterceptor`s advised functions use while handling the
 * call are created from it.
 *
 * ```
 * install(KaopCallScope) {
 *     interceptor { call -> AuditInterceptor(user = call.principal<UserIdPrincipal>()?.name) }
 * }
 * ```
 *
 * Suspend functions see the call scope on every platform; regular functions on the JVM (see
 * `withInterceptorScope`). Interceptors not configured here are resolved as usual (provider, then constructor).
 */
val KaopCallScope = createApplicationPlugin("KaopCallScope", ::KaopCallScopeConfig) {
    val factories = pluginConfig.factories.toMap()
    application.intercept(ApplicationCallPipeline.Setup) {
        withInterceptorScope(CallInterceptorScope(context, factories)) { proceed() }
    }
}

private class CallInterceptorScope(
    private val call: ApplicationCall,
    private val factories: Map<KClass<out Interceptor>, (ApplicationCall) -> Interceptor>,
) : InterceptorScope {
    // One call is handled by one coroutine at a time; the map only avoids re-creating interceptors per call.
    private val created = HashMap<KClass<out Interceptor>, Interceptor>()

    override fun get(type: KClass<out Interceptor>): Interceptor? =
        created[type] ?: factories[type]?.invoke(call)?.also { created[type] = it }
}
