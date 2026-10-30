package sample
class Example {
    private var amount = 1
    fun run(x: Int): Int { return x + amount }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
