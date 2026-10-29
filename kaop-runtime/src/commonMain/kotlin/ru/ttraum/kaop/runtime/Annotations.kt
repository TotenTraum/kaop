package ru.ttraum.kaop.runtime

import kotlin.reflect.KClass

/**
 * Meta-annotation that turns an annotation class into a pointcut.
 *
 * ```
 * @Advice(LoggingInterceptor::class)
 * annotation class Logged
 * ```
 *
 * Every function marked with `@Logged` (or declared in a class marked with `@Logged`)
 * is woven at compile time so that [LoggingInterceptor] runs "around" it.
 *
 * The interceptor must be an `object` or a class with a public no-arg constructor. It must implement
 * [MethodInterceptor] to advise regular functions and [SuspendMethodInterceptor] to advise `suspend` ones.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class Advice(val interceptor: KClass<out Interceptor>)

/** Excludes a function from weaving when its enclosing class carries an advice annotation. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class NoAdvice
