package ru.ttraum.kaop.kmp

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.request.header
import io.ktor.server.application.install
import ru.ttraum.kaop.ktor.KaopCallScope
import ru.ttraum.kaop.ktor.kaop
import ru.ttraum.kaop.ktor.kaopPreload
import ru.ttraum.kaop.runtime.Kaop
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorTest {
    @AfterTest
    fun reset() = Kaop.uninstall()

    @Test
    fun interceptorDependenciesComeFromKtorDi() = testApplication {
        val log = AuditLog()
        application {
            dependencies {
                provide<AuditLog> { log }
                provide<AuditInterceptor> { AuditInterceptor(resolve(), "ktor") }
            }
            kaop()
            routing {
                get("/withdraw") { call.respondText(Accounts().withdraw(30).toString()) }
            }
        }

        assertEquals("70", client.get("/withdraw").bodyAsText())
        assertEquals(listOf("ktor called withdraw[30]"), log.entries)
    }

    @Test
    fun ktorDefinitionOverridesTheConstructor() = testApplication {
        application {
            dependencies { provide<TagInterceptor> { TagInterceptor("ktor") } }
            kaop()
            routing { get("/greet") { call.respondText(Accounts().greet("ann")) } }
        }
        assertEquals("[ktor] hi ann", client.get("/greet").bodyAsText())
    }

    @Test
    fun unregisteredInterceptorFallsBackToTheConstructor() = testApplication {
        application {
            kaop()
            routing { get("/greet") { call.respondText(Accounts().greet("ann")) } }
        }
        assertEquals("[default] hi ann", client.get("/greet").bodyAsText())
    }

    @Test
    fun preloadResolvesUpFront() = testApplication {
        val log = AuditLog()
        application {
            dependencies {
                provide<AuditLog> { log }
                provide<AuditInterceptor> { AuditInterceptor(resolve(), "preloaded") }
            }
            kaopPreload(AuditInterceptor::class)
            routing {
                get("/withdraw") { call.respondText(Accounts().withdraw(1).toString()) }
            }
        }
        assertEquals("99", client.get("/withdraw").bodyAsText())
        assertEquals(listOf("preloaded called withdraw[1]"), log.entries)
    }

    @Test
    fun preloadOfAMissingInterceptorFailsTheStart() {
        val error = assertFails {
            testApplication {
                application { kaopPreload(AuditInterceptor::class) }
                client.get("/")
            }
        }
        assertTrue("AuditInterceptor" in error.toString(), error.toString())
    }

    @Test
    fun stoppingTheApplicationUninstallsTheProvider() {
        testApplication {
            application { kaop() }
            client.get("/")
            assertTrue(Kaop.provider != null)
        }
        assertNull(Kaop.provider)
    }

    @Test
    fun everyCallIsAnInterceptorScope() = testApplication {
        application {
            install(KaopCallScope) {
                interceptor { call -> UserInterceptor(call.request.headers["X-User"] ?: "nobody") }
            }
            routing {
                get("/me") { call.respondText(Session().whoamiAsync() + " / " + Session().whoami()) }
            }
        }

        assertEquals("suspend as alice / regular as alice", client.get("/me") { header("X-User", "alice") }.bodyAsText())
        assertEquals("suspend as bob / regular as bob", client.get("/me") { header("X-User", "bob") }.bodyAsText())
        assertEquals("suspend as nobody / regular as nobody", client.get("/me").bodyAsText())
        assertEquals("regular as anonymous", Session().whoami()) // outside a call
    }
}
