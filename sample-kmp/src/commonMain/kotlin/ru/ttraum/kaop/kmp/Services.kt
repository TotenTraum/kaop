package ru.ttraum.kaop.kmp

import ru.ttraum.kaop.runtime.NoAdvice
import kotlinx.coroutines.delay

class Calculator {
    @Logged
    fun add(a: Int, b: Int): Int = a + b

    @Logged
    fun sign(x: Int): String {
        if (x < 0) return "negative"
        return if (x == 0) "zero" else "positive"
    }

    @Logged
    fun <T : Comparable<T>> max(a: T, b: T): T = if (a >= b) a else b

    @Logged
    fun divide(a: Double, b: Double): Double {
        if (b == 0.0) throw IllegalArgumentException("division by zero")
        return a / b
    }

    @Logged
    fun greet(name: String, greeting: String = "Hello"): String = "$greeting, $name!"

    @Logged
    fun find(key: String): String? = null

    var counter = 0

    @Logged
    fun increment() {
        counter++
    }

    @Logged
    fun sumWithLocal(xs: List<Int>): Int {
        var total = 0
        fun addUp(v: Int) { total += v }
        xs.forEach { addUp(it) } // inline lambda + local function inside a woven body
        return total
    }
}

class Config {
    @get:Logged
    val defaultGetter: String = "a"

    @get:Logged
    val customGetter: String get() = "b"
}

class Fibonacci {
    var invocations = 0

    @Cached
    fun fib(n: Int): Long {
        invocations++
        return if (n < 2) n.toLong() else fib(n - 1) + fib(n - 2)
    }
}

@Logged
class Repository {
    fun load(id: Long): String = "item-$id"

    @NoAdvice
    fun internalOnly(): String = "hidden"

    suspend fun loadAsync(id: Long): String {
        delay(10)
        return "async-$id"
    }
}

class Flaky(private val failures: Int) {
    var attempts = 0

    @Retry(times = 5)
    fun call(): String {
        attempts++
        if (attempts <= failures) error("fail #$attempts")
        return "ok after $attempts"
    }
}

/** Lets platform tests observe where the body of [RemoteApi.onDefaultDispatcher] runs. */
object DispatcherProbe {
    var onBody: () -> Unit = {}
}

class RemoteApi {
    @Logged
    suspend fun fetch(id: Int): String {
        if (id < 0) return "invalid" // early return from a suspend body
        delay(10)
        return "data-$id"
    }

    @Logged
    suspend fun fail(): Nothing {
        delay(1)
        error("remote failure")
    }

    @OnDefaultDispatcher
    suspend fun onDefaultDispatcher(): String {
        DispatcherProbe.onBody()
        return "switched"
    }

    // A generic suspend function: its join point is a local class inside a generic function with a suspend member.
    @Logged
    suspend fun <T> echo(value: T): T {
        delay(1)
        return value
    }

    @Timeout(millis = 50)
    suspend fun slow(ms: Long): String {
        delay(ms)
        return "done in $ms"
    }
}

@TrimStrings
@Logged
fun register(user: String, age: Int): String = "[$user:$age]"

@Logged
fun String.shout(times: Int): String = uppercase().repeat(times)

@Logged
suspend fun Int.doubleLater(): Int {
    delay(1)
    return this * 2
}

@PositiveInts
fun label(name: String, count: Int): String = "$name x$count"

@Probed
fun probed(name: String, nickname: String?, count: Int): String = "$name/$nickname/$count"
