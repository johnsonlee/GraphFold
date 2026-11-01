package sample
import java.util.function.Supplier
class Example {
    fun run(): String {
        return (object : Supplier<String> { override fun get(): String { if (Ab.enabled("feature")) { return "callback=$this" }; return "live" } }).get()
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
