package ru.ttraum.kaop.kmp

import kotlinx.coroutines.delay
import ru.ttraum.kaop.runtime.Advice
import ru.ttraum.kaop.runtime.InterceptorScope
import ru.ttraum.kaop.runtime.JoinPoint
import ru.ttraum.kaop.runtime.MethodInterceptor
import ru.ttraum.kaop.runtime.ScopedInterceptor
import ru.ttraum.kaop.runtime.SuspendJoinPoint
import ru.ttraum.kaop.runtime.SuspendMethodInterceptor

/** Tags the result with the user of the current scope (e.g. the HTTP request). */
@Advice(UserInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class AsUser

@ScopedInterceptor
class UserInterceptor(private val user: String = "anonymous") : MethodInterceptor, SuspendMethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? = "${joinPoint.proceed()} as $user"

    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? = "${joinPoint.proceed()} as $user"
}

class Session {
    @AsUser
    fun whoami(): String = "regular"

    @AsUser
    suspend fun whoamiAsync(): String {
        delay(1)
        return "suspend"
    }
}

fun userScope(name: String) = InterceptorScope { type -> if (type == UserInterceptor::class) UserInterceptor(name) else null }
