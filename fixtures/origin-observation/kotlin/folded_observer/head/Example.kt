package sample
import java.util.function.Supplier
class Example {
    fun run(): String {
        if (Ab.enabled("feature")) { (object : Supplier<String> { override fun get(): String = "unused" }).get() }
        return (object : Supplier<String> { override fun get(): String { if (Ab.enabled("feature")) { return toString() }; return "live" } }).get()
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
