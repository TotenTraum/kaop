package ru.ttraum.kaop.kmp

import ru.ttraum.kaop.runtime.InterceptorProvider
import ru.ttraum.kaop.runtime.Kaop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderTest {
    @BeforeTest
    @AfterTest
    fun reset() = Kaop.uninstall()

    private fun providing(vararg instances: Pair<kotlin.reflect.KClass<*>, Any>) =
        InterceptorProvider { type -> instances.firstOrNull { it.first == type }?.second as? ru.ttraum.kaop.runtime.Interceptor }

    @Test
    fun interceptorWithDependenciesNeedsAProvider() {
        val error = assertFailsWith<IllegalStateException> { Accounts().withdraw(10) }
        assertTrue("AuditInterceptor (used by ru.ttraum.kaop.kmp.Accounts.withdraw) is not provided" in error.message!!, error.message)
    }

    @Test
    fun providerSuppliesInterceptorWithDependencies() {
        val log = AuditLog()
        Kaop.install(providing(AuditInterceptor::class to AuditInterceptor(log, "alice")))

        assertEquals(90, Accounts().withdraw(10))
        assertEquals(listOf("alice called withdraw[10]"), log.entries)
    }

    @Test
    fun reinstallingReplacesTheCachedInstance() {
        val log = AuditLog()
        Kaop.install(providing(AuditInterceptor::class to AuditInterceptor(log, "alice")))
        Accounts().withdraw(1)
        Kaop.install(providing(AuditInterceptor::class to AuditInterceptor(log, "bob")))
        Accounts().withdraw(2)

        assertEquals(listOf("alice called withdraw[1]", "bob called withdraw[2]"), log.entries)
    }

    @Test
    fun providerOverridesTheNoArgConstructorAndUninstallRestoresIt() {
        assertEquals("[default] hi ann", Accounts().greet("ann"))

        Kaop.install(providing(TagInterceptor::class to TagInterceptor("provided")))
        assertEquals("[provided] hi ann", Accounts().greet("ann"))

        Kaop.uninstall()
        assertEquals("[default] hi ann", Accounts().greet("ann"))
    }

    @Test
    fun providerReturningNullFallsBackToTheConstructor() {
        Kaop.install { null }
        assertEquals("[default] hi ann", Accounts().greet("ann"))
    }

    @Test
    fun providerReturningWrongTypeIsAnError() {
        Kaop.install { TagInterceptor("wrong") }
        val error = assertFailsWith<IllegalStateException> { Accounts().withdraw(10) }
        assertEquals("InterceptorProvider returned TagInterceptor for AuditInterceptor", error.message)
    }
}
