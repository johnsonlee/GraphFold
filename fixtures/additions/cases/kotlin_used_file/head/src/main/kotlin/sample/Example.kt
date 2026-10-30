package sample
class Example {
    fun run(x: Int): Int { return Additional().apply(x) }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
