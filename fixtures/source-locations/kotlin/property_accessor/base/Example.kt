package sample
class Example {
    fun gate() = Ab.enabled("feature")
    val value: Int get() = 1
    fun run(): Int = value
}
