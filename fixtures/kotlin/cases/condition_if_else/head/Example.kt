package sample

class Example {
    fun run(x: Int): Int {
        if (x >= 0) return 1
        else return 2
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
