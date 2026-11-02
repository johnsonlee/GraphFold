package sample

import kotlin.coroutines.*

class Example {
    fun run(x: Int): Int {
        var result = 0
        val action: suspend () -> Int = { x + if (Ab.enabled("feature")) 2 else 1 }
        action.startCoroutine(object : Continuation<Int> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<Int>) { result = outcome.getOrThrow() }
        })
        return result
    }
}
