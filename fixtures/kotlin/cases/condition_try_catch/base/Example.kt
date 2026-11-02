package sample

class Example {
    fun run(x: Int): Int {
        try { return 10 / x }
        catch (error: ArithmeticException) { return -1 }
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
