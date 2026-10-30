package sample
class Example {
    private val value = 2
    fun run(x: Int): Int { return x + value }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
