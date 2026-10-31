package sample
class Example {
    fun gate() = Ab.enabled("feature")
    val value: Int get() = 2
    fun run(): Int = value
}
