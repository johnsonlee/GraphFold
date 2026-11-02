package sample

class Example {
    fun run(x: Int): Int {
        if (x < 0) return -1
        return x * 2
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
