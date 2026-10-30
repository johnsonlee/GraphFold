package sample
interface Mapper<T> { fun map(value: T): T }
class Example : Mapper<String> {
    override fun map(value: String): String = if (Ab.enabled("feature")) value.take(1) else value
    fun run(): String = map("example")
}
