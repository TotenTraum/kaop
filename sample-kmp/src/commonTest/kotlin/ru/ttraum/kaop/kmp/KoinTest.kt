package ru.ttraum.kaop.kmp

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlinx.coroutines.test.runTest
import ru.ttraum.kaop.koin.asInterceptorScope
import ru.ttraum.kaop.koin.kaop
import ru.ttraum.kaop.runtime.withInterceptorScope
import ru.ttraum.kaop.runtime.Kaop
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KoinTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
        Kaop.uninstall()
    }

    @Test
    fun interceptorDependenciesComeFromKoin() {
        val log = AuditLog()
        startKoin {
            modules(module {
                single { log }
                single { AuditInterceptor(get(), "koin") }
            })
            kaop()
        }

        assertEquals(95, Accounts().withdraw(5))
        assertEquals(listOf("koin called withdraw[5]"), log.entries)
    }

    @Test
    fun restartingKoinPicksUpNewDefinitions() {
        val log = AuditLog()
        startKoin {
            modules(module { single { AuditInterceptor(log, "first") } })
            kaop()
        }
        Accounts().withdraw(1)

        stopKoin()
        startKoin {
            modules(module { single { AuditInterceptor(log, "second") } })
            kaop()
        }
        Accounts().withdraw(2)

        assertEquals(listOf("first called withdraw[1]", "second called withdraw[2]"), log.entries)
    }

    @Test
    fun koinDefinitionOverridesTheConstructor() {
        startKoin {
            modules(module { single { TagInterceptor("koin") } })
            kaop()
        }
        assertEquals("[koin] hi ann", Accounts().greet("ann"))
    }

    @Test
    fun interceptorNotDefinedInKoinFallsBackToTheConstructor() {
        startKoin {
            modules(module { })
            kaop()
        }
        assertEquals("[default] hi ann", Accounts().greet("ann"))
    }

    @Test
    fun koinScopeSuppliesScopedInterceptors() = runTest {
        val koin = startKoin {
            modules(module {
                scope(named("request")) { scoped { UserInterceptor("request-user") } }
            })
            kaop()
        }.koin
        val scope = koin.createScope("request-1", named("request"))

        assertEquals("suspend as request-user", withInterceptorScope(scope.asInterceptorScope()) { Session().whoamiAsync() })
        assertEquals("suspend as anonymous", Session().whoamiAsync())
        scope.close()
    }
}
