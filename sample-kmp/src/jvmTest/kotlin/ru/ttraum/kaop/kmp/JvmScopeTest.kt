package ru.ttraum.kaop.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.ttraum.kaop.runtime.withInterceptorScope
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmScopeTest {
    @Test
    fun suspendInterceptorMovesTheBodyToAnotherThread() = runBlocking {
        val caller = Thread.currentThread()
        var bodyThread: Thread? = null
        DispatcherProbe.onBody = { bodyThread = Thread.currentThread() }
        RemoteApi().onDefaultDispatcher()
        DispatcherProbe.onBody = {}
        assert(bodyThread != null && bodyThread != caller) { "body ran on the caller thread" }
    }

    @Test
    fun regularFunctionsSeeTheCoroutineScopeOnAnyThread() = runBlocking {
        val result = withInterceptorScope(userScope("dave")) {
            withContext(Dispatchers.Default) { Session().whoami() }   // regular function, another thread
        }
        assertEquals("regular as dave", result)
        assertEquals("regular as anonymous", Session().whoami())
    }
}
