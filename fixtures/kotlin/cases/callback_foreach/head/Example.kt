package sample

class Example {
    fun run(values: List<Int>): Int {
        val sum = intArrayOf(0)
        values.forEach(java.util.function.Consumer { value -> sum[0] += value * 2 })
        return sum[0]
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
