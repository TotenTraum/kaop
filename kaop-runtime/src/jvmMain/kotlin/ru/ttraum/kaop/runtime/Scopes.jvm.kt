package ru.ttraum.kaop.runtime

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

private val threadLocalScope = ThreadLocal<InterceptorScope?>()

internal actual var threadScope: InterceptorScope?
    get() = threadLocalScope.get()
    set(value) = threadLocalScope.set(value)

// asContextElement sets the thread-local whenever the coroutine runs, so regular functions see the scope too.
internal actual fun scopeContext(scope: InterceptorScope): CoroutineContext =
    KaopScopeElement(scope) + threadLocalScope.asContextElement(scope)
