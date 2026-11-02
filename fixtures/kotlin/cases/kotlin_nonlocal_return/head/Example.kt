package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        calculate { if (x > 0) return 2 }; return x
    }
    private inline fun calculate(block: () -> Unit) { block() }
}
