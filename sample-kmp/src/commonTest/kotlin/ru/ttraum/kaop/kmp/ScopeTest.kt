package ru.ttraum.kaop.kmp

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import ru.ttraum.kaop.runtime.InterceptorScope
import ru.ttraum.kaop.runtime.Kaop
import ru.ttraum.kaop.runtime.inInterceptorScope
import ru.ttraum.kaop.runtime.withInterceptorScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeTest {
    @AfterTest
    fun reset() = Kaop.uninstall()

    @Test
    fun outsideAScopeTheInterceptorIsResolvedAsUsual() = runTest {
        assertEquals("regular as anonymous", Session().whoami())
        assertEquals("suspend as anonymous", Session().whoamiAsync())
    }

    @Test
    fun suspendFunctionsUseTheCoroutineScope() = runTest {
        assertEquals("suspend as alice", withInterceptorScope(userScope("alice")) { Session().whoamiAsync() })
        assertEquals("suspend as anonymous", Session().whoamiAsync())
    }

    @Test
    fun scopedInstancesAreNotCachedAcrossScopes() = runTest {
        assertEquals("suspend as alice", withInterceptorScope(userScope("alice")) { Session().whoamiAsync() })
        assertEquals("suspend as bob", withInterceptorScope(userScope("bob")) { Session().whoamiAsync() })
    }

    @Test
    fun innerScopeWinsAndOuterIsRestored() = runTest {
        withInterceptorScope(userScope("outer")) {
            assertEquals("suspend as inner", withInterceptorScope(userScope("inner")) { Session().whoamiAsync() })
            assertEquals("suspend as outer", Session().whoamiAsync())
        }
    }

    @Test
    fun concurrentCoroutinesKeepTheirOwnScopes() = runTest {
        val alice = async { withInterceptorScope(userScope("alice")) { delay(10); Session().whoamiAsync() } }
        val bob = async { withInterceptorScope(userScope("bob")) { Session().whoamiAsync() } }
        assertEquals("suspend as alice", alice.await())
        assertEquals("suspend as bob", bob.await())
    }

    @Test
    fun regularFunctionsUseTheThreadScope() {
        assertEquals("regular as carol", inInterceptorScope(userScope("carol")) { Session().whoami() })
        assertEquals("regular as anonymous", Session().whoami())
    }

    @Test
    fun scopeReturningNullFallsBackToTheProvider() = runTest {
        Kaop.install { type -> if (type == UserInterceptor::class) UserInterceptor("global") else null }
        assertEquals("suspend as global", withInterceptorScope({ null }) { Session().whoamiAsync() })
    }
}
