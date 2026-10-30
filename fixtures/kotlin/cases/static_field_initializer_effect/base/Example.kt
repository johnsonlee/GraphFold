package sample
class Example {
    companion object {
        private val unused = initialize()
        private fun initialize(): Int { System.out.println("before"); return 1 }
    }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
