package ru.ttraum.kaop.runtime

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * Supplies interceptor instances, typically from a DI container (see `kaop-koin`).
 *
 * Called for interceptors that are classes (not `object`s), once per advised function and installation;
 * the result is cached. Return `null` for "not mine": kaop then falls back to the class's no-arg
 * constructor, if it has one.
 */
fun interface InterceptorProvider {
    fun get(type: KClass<out Interceptor>): Interceptor?
}

/** Global configuration of kaop. */
object Kaop {
    /**
     * Makes [provider] the source of class-based interceptors. Interceptors resolved before are dropped
     * and resolved again on their next call, so re-installing after a container restart is enough.
     */
    fun install(provider: InterceptorProvider) {
        installation = Installation(provider)
    }

    /** Removes the provider: class-based interceptors are created with their no-arg constructors again. */
    fun uninstall() {
        installation = Installation(null)
    }

    /** The currently installed provider, or `null`. */
    val provider: InterceptorProvider? get() = installation.provider
}

/**
 * One call of [Kaop.install] / [Kaop.uninstall]. Resolved interceptors remember the installation they came
 * from; a new object on every change makes "is my cache still valid?" a single identity check.
 */
internal class Installation(val provider: InterceptorProvider?)

@Volatile
internal var installation: Installation = Installation(null)

/**
 * An interceptor that is a class: resolved through the [InterceptorProvider], falling back to [fallback]
 * (its no-arg constructor, `null` if it has none). Created by woven code. **Not intended to be used by hand.**
 */
@InternalKaopApi
class ProvidedInterceptor(
    val type: KClass<out Interceptor>,
    val fallback: (() -> Interceptor)?,
)
