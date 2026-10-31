package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            var remaining = x
            var sum = 0
            do { sum += remaining; remaining-- } while (remaining > 0)
            return sum
        }
        return 0
    }
}
