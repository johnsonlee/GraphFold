package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return x.adjusted
    }
    private val Int.adjusted: Int get() = this + 1
}
