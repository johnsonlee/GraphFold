package sample
class Example {
    inline fun calculate(value: Int): Int = value + if (Ab.enabled("feature")) 1 else 0
    fun run(): Int = calculate(10)
}
