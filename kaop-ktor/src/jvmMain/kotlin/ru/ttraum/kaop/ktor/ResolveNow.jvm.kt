package ru.ttraum.kaop.ktor

import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.getBlocking
import ru.ttraum.kaop.runtime.Interceptor
import kotlin.reflect.KClass

// Same mechanism as Ktor's own `val x: X by dependencies`; runs once per advised function and installation.
internal actual fun resolveNow(dependencies: DependencyRegistry, key: DependencyKey, type: KClass<out Interceptor>): Interceptor =
    dependencies.getBlocking(key)
