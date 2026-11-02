package sample
import java.util.function.Supplier
class Example {
    fun run(): String {
        return (object : Supplier<String> { override fun get(): String = "live" }).get()
    }
    fun unrelated(): Throwable = RuntimeException()
    fun gate(): Boolean = Ab.enabled("feature")
}
