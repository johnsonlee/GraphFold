package sample
class Example {
    companion object { init { System.out.println("before") } }
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
