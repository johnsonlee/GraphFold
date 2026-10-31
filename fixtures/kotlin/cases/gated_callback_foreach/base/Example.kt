package sample

class Example {
    fun run(values: List<Int>): Int {
        if (Ab.enabled("feature")) {
            val sum = intArrayOf(0)
            values.forEach(java.util.function.Consumer { value -> sum[0] += value })
            return sum[0]
        }
        return 0
    }
}
