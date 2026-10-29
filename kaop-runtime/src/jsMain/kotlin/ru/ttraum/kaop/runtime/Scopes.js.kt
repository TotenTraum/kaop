package ru.ttraum.kaop.runtime

import kotlin.coroutines.CoroutineContext

// JS is single-threaded; inInterceptorScope restores the previous value in `finally`.
internal actual var threadScope: InterceptorScope? = null

internal actual fun scopeContext(scope: InterceptorScope): CoroutineContext = KaopScopeElement(scope)
