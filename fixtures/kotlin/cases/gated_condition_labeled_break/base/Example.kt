package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            var sum = 0
            outer@ for (i in 0 until x) {
                for (j in 0 until x) { if (j == 3) break@outer; sum++ }
            }
            return sum
        }
        return 0
    }
}
