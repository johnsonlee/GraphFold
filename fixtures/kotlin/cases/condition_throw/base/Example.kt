package sample

class Example {
    fun run(x: Int): Int {
        if (x < 0) throw IllegalArgumentException()
        return x
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
