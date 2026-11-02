package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return (x + 1).adjusted
        }
        return -1
    }
    private val Int.adjusted: Int get() = this + 1
}
