package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return calculate { x + 2 }
    }
    private inline fun calculate(block: () -> Int): Int = block()
}
