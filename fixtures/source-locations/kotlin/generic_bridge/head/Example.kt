package sample
interface Mapper<T> { fun map(value: T): T }
class Example : Mapper<String> {
    fun gate() = Ab.enabled("feature")
    override fun map(value: String): String = value.take(2)
    fun run(): String = map("example")
}
