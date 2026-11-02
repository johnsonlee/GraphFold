package sample
class Example {
    fun advance(x: Int): Int { return x + 1 }
    fun run(x: Int): Int { return advance(x) }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
