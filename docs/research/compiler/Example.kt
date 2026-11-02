// 😀	UTF-16 and physical line-ending provenance fixture.
package sample
import kotlin.coroutines.*
interface Callback { fun run(): Int }
class Example {
    val first = object : Callback { override fun run() = 1 }; val second = object : Callback { override fun run() = 2 }
    fun local(): Callback = object : Callback {
        override fun run(): Int { val nested = object : Callback { override fun run() = 3 }; return nested.run() }
    }
    init { val initialized = object : Callback { override fun run() = 4 }; initialized.run() }
    companion object { val shared = object : Callback { override fun run() = 5 } }
}
inline fun copied(): Callback = object : Callback { override fun run() = 6 }
fun caller() = copied()
inline fun inlined(crossinline block: () -> Int): Callback = object : Callback { override fun run() = block() }
fun callInlined() = inlined { 7 }

class SuspendCallback {
    fun run(x: Int): Int {
        var result = 0
        val action: suspend () -> Int = { x + 1 }
        action.startCoroutine(object : Continuation<Int> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<Int>) { result = outcome.getOrThrow() }
        })
        return result
    }
}
