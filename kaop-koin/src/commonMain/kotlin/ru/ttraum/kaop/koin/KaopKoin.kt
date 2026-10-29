package ru.ttraum.kaop.koin

import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.scope.Scope
import ru.ttraum.kaop.runtime.Interceptor
import ru.ttraum.kaop.runtime.InterceptorProvider
import ru.ttraum.kaop.runtime.InterceptorScope
import ru.ttraum.kaop.runtime.Kaop
import kotlin.reflect.KClass

/**
 * Resolves class-based interceptors from [koin]. An interceptor that is not defined in Koin falls back to its
 * no-arg constructor (if it has one).
 */
class KoinInterceptorProvider(private val koin: Koin) : InterceptorProvider {
    override fun get(type: KClass<out Interceptor>): Interceptor? = koin.getOrNull(type)
}

/**
 * Makes this Koin application the source of kaop interceptors:
 *
 * ```
 * startKoin {
 *     modules(module { single { MetricsInterceptor(get()) } })
 *     kaop()
 * }
 * ```
 *
 * Interceptors resolved from a previous installation are dropped, so calling it again after a Koin restart
 * (`stopKoin()` + `startKoin { ...; kaop() }`) picks up the new definitions. Call [Kaop.uninstall] after
 * `stopKoin()` if kaop should stop using this Koin without a new one being started.
 */
fun KoinApplication.kaop(): KoinApplication = apply {
    Kaop.install(KoinInterceptorProvider(koin))
}

/**
 * Uses this Koin scope as the source of `@ScopedInterceptor`s:
 *
 * ```
 * module { scope(named("request")) { scoped { UserInterceptor(get()) } } }
 *
 * val scope = koin.createScope(requestId, named("request"))
 * withInterceptorScope(scope.asInterceptorScope()) { handle() }
 * ```
 */
fun Scope.asInterceptorScope(): InterceptorScope = InterceptorScope { type -> getOrNull(type) }
