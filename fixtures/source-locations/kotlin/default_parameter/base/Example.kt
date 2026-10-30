package sample
class Example {
    fun gate() = Ab.enabled("feature")
    fun calculate(value: Int = 1): Int = value
    fun run(): Int = calculate()
}
