package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            calculate { if (x > 0) return 1 }; return x
        }
        return -1
    }
    private inline fun calculate(block: () -> Unit) { block() }
}
