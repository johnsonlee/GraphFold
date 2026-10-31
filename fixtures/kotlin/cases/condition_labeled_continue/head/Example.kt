package sample
class Example {
    fun run(x: Int): Int {
        var sum = 0
        outer@ for (i in 0 until x) {
            for (j in 0 until x) { if (j == 4) continue@outer; sum++ }
        }
        return sum
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
