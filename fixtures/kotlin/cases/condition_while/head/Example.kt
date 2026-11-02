package sample

class Example {
    fun run(x: Int): Int {
        var remaining = x
        var sum = 0
        while (remaining > 1) { sum += remaining; remaining-- }
        return sum
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
