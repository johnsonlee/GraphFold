package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return calculate { x + 1 }
        }
        return -1
    }
    private inline fun calculate(block: () -> Int): Int = block()
}
