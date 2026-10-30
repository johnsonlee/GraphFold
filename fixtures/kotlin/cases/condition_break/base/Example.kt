package sample

class Example {
    fun run(x: Int): Int {
        var sum = 0
        for (i in 0 until x) { if (i == 3) break; sum += i }
        return sum
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
