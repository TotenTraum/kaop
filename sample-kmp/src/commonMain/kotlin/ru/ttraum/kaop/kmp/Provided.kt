package ru.ttraum.kaop.kmp

import ru.ttraum.kaop.runtime.Advice
import ru.ttraum.kaop.runtime.JoinPoint
import ru.ttraum.kaop.runtime.MethodInterceptor

/** An interceptor with dependencies: it can only come from an InterceptorProvider (a DI container). */
@Advice(AuditInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Audited

class AuditLog {
    val entries = mutableListOf<String>()
}

class AuditInterceptor(private val log: AuditLog, private val actor: String) : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? {
        log.entries += "$actor called ${joinPoint.method.name}${joinPoint.args}"
        return joinPoint.proceed()
    }
}

/** Has a constructor usable without arguments, so it works without a provider but can be overridden by one. */
@Advice(TagInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class Tagged

class TagInterceptor(private val tag: String = "default") : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any = "[$tag] ${joinPoint.proceed()}"
}

class Accounts {
    @Audited
    fun withdraw(amount: Int): Int = 100 - amount

    @Tagged
    fun greet(name: String): String = "hi $name"
}
