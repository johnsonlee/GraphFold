package sample

class Example {
    fun run(x: Int): Int {
        return if (x > 0) 1 else 0
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
