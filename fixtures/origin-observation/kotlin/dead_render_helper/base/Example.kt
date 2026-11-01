package sample
import java.util.function.Supplier
class Example {
    fun run(): String {
        return (object : Supplier<String> { override fun get(): String = "live" }).get()
    }
    private fun unused(value: Any): String = value.toString()
    fun gate(): Boolean = Ab.enabled("feature")
}
