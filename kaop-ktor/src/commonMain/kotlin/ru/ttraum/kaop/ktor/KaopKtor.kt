package ru.ttraum.kaop.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import io.ktor.util.AttributeKey
import io.ktor.util.reflect.TypeInfo
import ru.ttraum.kaop.runtime.Interceptor
import ru.ttraum.kaop.runtime.InterceptorProvider
import ru.ttraum.kaop.runtime.Kaop
import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * Resolves class-based interceptors from an application's Ktor DI [dependencies]. An interceptor that is not
 * registered there falls back to its no-arg constructor (if it has one).
 *
 * Ktor DI resolves asynchronously while interceptors are needed synchronously: on the JVM a missing instance is
 * resolved in place (like Ktor's own `val x: X by dependencies`); on other platforms it must be loaded up front
 * with [kaopPreload].
 */
class KtorInterceptorProvider internal constructor(
    private val dependencies: DependencyRegistry,
) : InterceptorProvider {
    @Volatile
    private var preloaded: Map<KClass<out Interceptor>, Interceptor> = emptyMap()

    override fun get(type: KClass<out Interceptor>): Interceptor? {
        preloaded[type]?.let { return it }
        val key = keyOf(type)
        if (!dependencies.contains(key)) return null
        return resolveNow(dependencies, key, type)
    }

    internal suspend fun preload(type: KClass<out Interceptor>) {
        val instance: Interceptor = dependencies.get(keyOf(type))
        preloaded = preloaded + (type to instance)
    }
}

internal fun keyOf(type: KClass<out Interceptor>) = DependencyKey(TypeInfo(type))

/** Resolves [key] without suspending; platform-specific (Ktor DI itself is asynchronous). */
internal expect fun resolveNow(dependencies: DependencyRegistry, key: DependencyKey, type: KClass<out Interceptor>): Interceptor

private val ProviderKey = AttributeKey<KtorInterceptorProvider>("ru.ttraum.kaop.ktor.provider")

/**
 * Makes this application's Ktor DI [dependencies] the source of kaop interceptors:
 *
 * ```
 * fun Application.module() {
 *     dependencies { provide<MetricsInterceptor> { MetricsInterceptor(resolve()) } }
 *     kaop()
 * }
 * ```
 *
 * The provider is removed again when the application stops, so every `testApplication` starts clean.
 * kaop is global: with several applications running in one process, the last one to call `kaop()` wins.
 */
fun Application.kaop(): KtorInterceptorProvider {
    attributes.getOrNull(ProviderKey)?.let { return it }
    val provider = KtorInterceptorProvider(dependencies)
    attributes.put(ProviderKey, provider)
    Kaop.install(provider)
    monitor.subscribe(ApplicationStopped) {
        if (Kaop.provider === provider) Kaop.uninstall()
    }
    return provider
}

/**
 * Resolves the given interceptors now (installing [kaop] if needed), so that a missing registration fails the
 * application start instead of the first advised call. Required on non-JVM platforms, optional on the JVM.
 * Call it from a `suspend fun Application.module()`.
 */
suspend fun Application.kaopPreload(vararg interceptors: KClass<out Interceptor>) {
    val provider = kaop()
    for (type in interceptors) provider.preload(type)
    Kaop.install(provider) // drop instances cached before the preload
}
