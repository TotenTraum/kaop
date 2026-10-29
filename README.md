# kaop

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Around-advice AOP for Kotlin, woven at compile time by a K2 compiler plugin (IR backend).

- Aspects are plain annotations: `@Logged fun load(id: Long)` — no proxies, no reflection, no bytecode post-processing.
- `suspend` functions are first-class: advice can suspend (`withTimeout`, `withContext`) and sees the real result.
- Kotlin Multiplatform: JVM, JS, macOS/iOS/Linux Native.
- Interceptors with dependencies from Koin, Ktor DI or any container; interceptors scoped to an HTTP request.
- On the JVM the JIT can inline the whole chain: an advised call costs about as much as a direct one.

Requires Kotlin **2.4.20** (the compiler plugin is built against a specific compiler version).

| Module | What it contains |
|---|---|
| `kaop-gradle-plugin` | Gradle plugin `ru.ttraum.kaop`: applies the compiler plugin and adds the runtime |
| `kaop-runtime` | `@Advice`, `@NoAdvice`, `MethodInterceptor` / `SuspendMethodInterceptor`, `JoinPoint` / `SuspendJoinPoint`, providers and scopes |
| `kaop-compiler-plugin` | The compiler plugin that rewrites advised functions |
| `kaop-koin` | Koin integration: interceptors with dependencies and Koin scopes |
| `kaop-ktor` | Ktor integration: interceptors from Ktor DI, every HTTP call as a scope |
| `sample-kmp` | Examples (logging, retry, cache, argument rewriting, DI, scopes) with tests on JVM, JS and Native |

## Setup

The plugin is published to Maven Central (not the Gradle Plugin Portal), so add it to the plugin repositories:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"          // or kotlin("multiplatform")
    id("ru.ttraum.kaop") version "0.1.0"
}

kaop {
    enabled = true                          // default
    dumpDir = layout.buildDirectory.dir("aop-dump")   // optional: woven code as Kotlin-like source
    addRuntimeDependency = true             // default: adds ru.ttraum:kaop-runtime of the same version
}
```

The plugin applies the compiler plugin to every Kotlin compilation (JVM, JS, Native) and adds `kaop-runtime`
(to `implementation`, or `commonMain` in a multiplatform project). For the integrations add
`ru.ttraum:kaop-koin` or `ru.ttraum:kaop-ktor` of the same version.

## Usage

```kotlin
@Advice(LoggingInterceptor::class)
annotation class Logged

object LoggingInterceptor : MethodInterceptor {
    override fun intercept(joinPoint: JoinPoint): Any? {
        println("-> ${joinPoint.method} ${joinPoint.args}")
        return joinPoint.proceed()          // or proceed(newArgs...), or skip it entirely
    }
}

class Service {
    @Logged fun load(id: Long): String = ...
}
```

- The annotation can go on a function, a property getter (`@get:Logged`), or a class (applies to every declared
  function; opt out with `@NoAdvice`).
- Several advice annotations form a chain: class-level ones are outermost, then function-level ones in declaration
  order.
- `joinPoint.annotation<Retry>()` returns the annotation instance with its arguments.
- Arguments: `args` (a list view), `argCount`, `arg<T>(i)` (safe cast, `null` on mismatch) and `requireArg<T>(i)`
  (throws `ClassCastException` naming the parameter and both types). `arg`/`requireArg` do not allocate.
- The interceptor is an `object`, or a class: then it is created once per advised function and reused (so it must
  be thread-safe) — by its no-arg constructor, or by a DI container (see below).

### Suspend functions

A `suspend` function is advised by interceptors implementing `SuspendMethodInterceptor`, whose `intercept`
and `proceed` are `suspend` — so advice can `delay`, `withContext`, `withTimeout`, etc.:

```kotlin
@Advice(TimeoutInterceptor::class)
annotation class Timeout(val millis: Long)

object TimeoutInterceptor : SuspendMethodInterceptor {
    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? =
        withTimeout(joinPoint.annotation<Timeout>().millis) { joinPoint.proceed() }
}
```

To advise both kinds with one annotation, implement both interfaces (see `LoggingInterceptor` in `sample-kmp`).
Binding an interceptor to a function kind it does not implement is a compile error.

### Interceptors with dependencies (DI)

An interceptor that is a class may take dependencies in its constructor. kaop asks the installed
`InterceptorProvider` for it (once per advised function, then cached) and falls back to a constructor that needs no
arguments; an interceptor without such a constructor must be provided.

```kotlin
class MetricsInterceptor(private val registry: MeterRegistry) : MethodInterceptor { ... }

// kaop-koin
startKoin {
    modules(module { single { MetricsInterceptor(get()) } })
    kaop()
}

// kaop-ktor (Ktor DI)
fun Application.module() {
    dependencies { provide<MetricsInterceptor> { MetricsInterceptor(resolve()) } }
    kaop()                                   // removed again when the application stops
}
suspend fun Application.module() {           // optional on the JVM, required on JS/Native:
    kaopPreload(MetricsInterceptor::class)   // resolve now, fail the start if something is missing
}

// any other container
Kaop.install { type -> container.getOrNull(type) }
```

`Kaop.install` / `Kaop.uninstall` drop the cached instances, so re-installing after a container restart (tests!)
picks up the new definitions. `object` interceptors never go through the provider.

### Scoped interceptors (per request, per job)

Mark an interceptor class `@ScopedInterceptor` when its instance depends on the current unit of work. It is then
looked up in the current `InterceptorScope` on every call (not cached per function); outside a scope it is
resolved like any other class. Unmarked interceptors pay nothing for this.

```kotlin
@ScopedInterceptor
class UserInterceptor(private val user: String = "anonymous") : MethodInterceptor { ... }

withInterceptorScope(InterceptorScope { type -> UserInterceptor("alice") }) { handle() }   // coroutines
inInterceptorScope(scope) { handleBlocking() }                                            // plain code

// kaop-ktor: every HTTP call is a scope
install(KaopCallScope) {
    interceptor { call -> UserInterceptor(call.request.headers["X-User"] ?: "anonymous") }
}

// kaop-koin: a Koin scope is a scope
withInterceptorScope(koinScope.asInterceptorScope()) { handle() }
```

The scope lives in the coroutine context, so concurrent coroutines keep their own. Advised `suspend` functions see
it on every platform; regular functions see it on the JVM (it is propagated to a thread-local on every thread the
coroutine runs on), on JS/Native only inside `inInterceptorScope`.

## How the weaving works

```kotlin
class C { @A @B fun f(a: X, b: Y): R { body } }      // @A -> object IA, @B -> class IB
// becomes (simplified)
class C {
    private static var aop$f$0: AdvisedMethod? = null // metadata, built on the first call

    fun f(a: X, b: Y): R {
        class AopJoinPoint1(...) : JoinPoint(...) {    // last step: the original body
            override fun invokeNext(target: Any?, args: Array<Any?>) = aopStep(target, args)
            fun aopStep(target: Any?, args: Array<Any?>): Any? {
                val self = target as C
                val a = args[0] as X; val b = args[1] as Y
                body                                   // this -> self, return@f -> return@aopStep
            }
        }
        class AopJoinPoint0(...) : JoinPoint(...) {    // every other step: the next interceptor
            override fun invokeNext(target: Any?, args: Array<Any?>) = aopStep(target, args)
            fun aopStep(target: Any?, args: Array<Any?>): Any? =
                (aop$f$0!!.interceptorAt(1) as IB).intercept(AopJoinPoint1(aop$f$0!!, target, args, 1))
        }
        val advised = aop$f$0 ?: AdvisedMethod(...).also { aop$f$0 = it }
        return IA.intercept(AopJoinPoint0(advised, this, arrayOf(a, b), 0)) as R
    }
}
```

The chain is spelled out per function, the generated classes capture nothing, and interceptors are called through
their concrete class: no call site is shared between advised functions. The JIT can therefore inline the interceptor,
`proceed()` and the body into `f`, and escape analysis removes the join point, the argument array and the boxes.
The body stays inside `f`, so type parameters and captured locals need no remapping.

To see the real generated code, set `kaop { dumpDir = ... }`.

## Limitations

- **Kotlin version.** The compiler plugin is built for Kotlin 2.4.20; other compiler versions are not supported.
- **Not supported:** `inline` and `tailrec` functions (compile error); constructors are not woven.
- **No inheritance of advice.** An annotation on an interface or abstract method does nothing (the compiler warns);
  put it on the implementation.
- **Global configuration.** `Kaop.install` is process-wide: with several applications (or parallel tests) in one
  process, the last installation wins.
- **Stack traces** of advised calls contain the generated `AopJoinPoint` frames.
- **Recompile on upgrade.** Code compiled with one kaop version must be recompiled for another: the generated code
  uses declarations marked `@InternalKaopApi` (using them by hand is a compile error).

## Development

```bash
./gradlew build -PaopDump
```

builds everything and runs all tests on the host's platforms; `-PaopDump` additionally verifies the generated IR
(`-Xverify-ir`) and writes the woven code of `sample-kmp` to `sample-kmp/build/aop-dump/`. The sample applies the
Gradle plugin by id from the included build `kaop-gradle-plugin`; the root build substitutes the published
`ru.ttraum:kaop-*` coordinates with the local projects.

Apple targets need a full Xcode installation to link test binaries.

### Publishing

Versions live in `gradle/libs.versions.toml`, Maven coordinates in `gradle.properties` (`buildInfo.*`). All `kaop-*`
modules (including the Gradle plugin and its plugin marker) are published with
[vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin):

```bash
./gradlew publishAllToMavenLocal
```

```bash
./gradlew publishAllToMavenCentral
```

Maven Central credentials and the signing key are read from `~/.gradle/gradle.properties` and are never stored in
this repository. Publications are signed only when a signing key is configured.

## License

[Apache License 2.0](LICENSE)
