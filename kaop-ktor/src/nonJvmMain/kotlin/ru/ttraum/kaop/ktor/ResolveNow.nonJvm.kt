package ru.ttraum.kaop.ktor

import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.DependencyRegistry
import ru.ttraum.kaop.runtime.Interceptor
import kotlin.reflect.KClass

// No blocking here: use the instance if Ktor DI already created it, otherwise it has to be preloaded.
internal actual fun resolveNow(dependencies: DependencyRegistry, key: DependencyKey, type: KClass<out Interceptor>): Interceptor {
    val deferred = dependencies.getDeferred<Interceptor>(key)
    check(deferred.isCompleted) {
        "Interceptor ${type.simpleName} is not resolved yet: call kaopPreload(${type.simpleName}::class) " +
            "in a suspend Application.module()"
    }
    return deferred.getCompleted()
}
