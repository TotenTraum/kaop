package ru.ttraum.kaop.runtime

import kotlin.coroutines.CoroutineContext
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object ThreadScopeHolder {
    var scope: InterceptorScope? = null
}

internal actual var threadScope: InterceptorScope?
    get() = ThreadScopeHolder.scope
    set(value) {
        ThreadScopeHolder.scope = value
    }

internal actual fun scopeContext(scope: InterceptorScope): CoroutineContext = KaopScopeElement(scope)
