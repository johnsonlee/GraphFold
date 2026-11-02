package sample
class Example {
    companion object {
        init { initialize() }
        private fun initialize() {}
    }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
