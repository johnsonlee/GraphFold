package sample
class Example {
    private val unused = initialize()
    private fun initialize(): Int { System.out.println("initialized"); return 1 }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
