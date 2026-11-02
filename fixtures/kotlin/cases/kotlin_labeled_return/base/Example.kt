package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return calculate action@{ if (x > 0) return@action 1; x }
    }
    private inline fun calculate(block: () -> Int): Int = block()
}
