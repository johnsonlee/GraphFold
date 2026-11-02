package sample
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
class Example {
    fun gate() = Ab.enabled("feature")
    var pending: Continuation<Int>? = null
    suspend fun run(): Int {
        val value = suspendCoroutine<Int> { continuation -> pending = continuation }
        return value + 1
    }
}
