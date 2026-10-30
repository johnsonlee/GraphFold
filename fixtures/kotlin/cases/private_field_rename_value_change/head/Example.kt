package sample
class Example {
    private var offset = 2
    fun run(x: Int): Int { return x + offset }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
