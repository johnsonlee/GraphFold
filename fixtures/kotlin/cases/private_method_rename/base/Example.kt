package sample
class Example {
    private fun increment(x: Int): Int { return x + 1 }
    fun run(x: Int): Int { return increment(x) }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
