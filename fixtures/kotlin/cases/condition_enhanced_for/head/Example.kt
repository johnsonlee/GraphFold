package sample

class Example {
    fun run(values: IntArray): Int {
        var sum = 0
        for (value in values) { if (value > 1) sum += value }
        return sum
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
