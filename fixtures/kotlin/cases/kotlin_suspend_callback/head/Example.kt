package sample

import kotlin.coroutines.*

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        var result = 0
        val action: suspend () -> Int = { x + 2 }
        action.startCoroutine(object : Continuation<Int> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<Int>) { result = outcome.getOrThrow() }
        })
        return result
    }
}
