package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            try { return 10 / x }
            catch (error: ArithmeticException) { return -1 }
        }
        return 0
    }
}
