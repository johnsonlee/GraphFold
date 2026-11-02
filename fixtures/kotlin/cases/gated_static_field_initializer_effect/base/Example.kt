package sample
class Example {
    companion object {
        private val unused = initialize()
        private fun initialize(): Int { if (Ab.enabled("feature")) System.out.println("before"); return 1 }
    }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
