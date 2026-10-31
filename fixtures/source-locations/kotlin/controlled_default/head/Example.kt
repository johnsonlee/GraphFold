package sample
class Example {
    fun calculate(value: Int = if (Ab.enabled("feature")) 2 else 0): Int = value
    fun run(): Int = calculate()
}
