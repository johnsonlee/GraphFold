package sample
class Example {
    fun gate() = Ab.enabled("feature")
    inline fun calculate(value: Int): Int = value + 2
    fun run(): Int = calculate(10)
}
