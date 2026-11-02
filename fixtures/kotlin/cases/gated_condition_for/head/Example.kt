package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            var sum = 0
            for (i in 0..x) sum += i
            return sum
        }
        return 0
    }
}
