package sample

class Example {
    fun run(x: Int): Int {
        var remaining = x
        var sum = 0
        do { sum += remaining; remaining-- } while (remaining > 1)
        return sum
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
