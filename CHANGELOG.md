# Changelog

## 0.1.0 — unreleased

First release.

- Compile-time around advice for Kotlin (K2 compiler plugin, IR backend), Kotlin 2.4.20.
- Advice through annotations meta-annotated with `@Advice`, on functions, property getters (`@get:`) and classes;
  `@NoAdvice` to opt out; chains of several interceptors.
- `JoinPoint`: target, method signature, annotation instance, arguments (`args`, `argCount`, `arg<T>(i)`,
  `requireArg<T>(i)`), `proceed()` and `proceed(newArgs)`.
- `suspend` functions through `SuspendMethodInterceptor` / `SuspendJoinPoint`.
- Kotlin Multiplatform: JVM, JS, macOS/iOS/Linux Native.
- Interceptors with dependencies through `InterceptorProvider` (`Kaop.install`), with `kaop-koin` and `kaop-ktor`
  integrations.
- Scoped interceptors (`@ScopedInterceptor`, `InterceptorScope`), with a per-call scope for Ktor (`KaopCallScope`)
  and Koin scopes.
- Gradle plugin `ru.ttraum.kaop`.
- Diagnostics: errors for unsupported functions (`inline`, `tailrec`) and mismatched interceptor kinds; a warning for
  advice on functions without a body.
