package sample
class Example {
    fun run(values: IntArray): Int {
        var sum = 0
        for (value in values) {
            sum += value
            if (Ab.enabled("feature")) sum += 2
        }
        return sum
    }
}
