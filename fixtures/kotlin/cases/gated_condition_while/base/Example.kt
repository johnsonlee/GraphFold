package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            var remaining = x
            var sum = 0
            while (remaining > 0) { sum += remaining; remaining-- }
            return sum
        }
        return 0
    }
}
