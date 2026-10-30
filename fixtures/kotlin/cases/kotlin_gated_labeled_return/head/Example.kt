package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return calculate action@{ if (x > 0) return@action 2; x }
        }
        return -1
    }
    private inline fun calculate(block: () -> Int): Int = block()
}
