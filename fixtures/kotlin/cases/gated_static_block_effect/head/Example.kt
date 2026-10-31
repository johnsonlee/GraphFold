package sample
class Example {
    companion object { init { if (Ab.enabled("feature")) System.out.println("after") } }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
