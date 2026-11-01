package sample
import java.util.function.Supplier
class Example {
    fun run(): String {
        return (object : Supplier<String> { override fun get(): String = capture().stackTrace[1].className }).get()
    }
    private fun capture(): Throwable = RuntimeException()
    fun gate(): Boolean = Ab.enabled("feature")
}
