package sample

class Example {
    fun run(values: IntArray): Int {
        if (Ab.enabled("feature")) {
            var sum = 0
            for (value in values) { if (value > 1) sum += value }
            return sum
        }
        return 0
    }
}
