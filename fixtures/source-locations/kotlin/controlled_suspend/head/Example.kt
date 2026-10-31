package sample
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
class Example {
    var pending: Continuation<Int>? = null
    suspend fun run(): Int {
        val value = suspendCoroutine<Int> { continuation -> pending = continuation }
        return value + if (Ab.enabled("feature")) 2 else 0
    }
}
