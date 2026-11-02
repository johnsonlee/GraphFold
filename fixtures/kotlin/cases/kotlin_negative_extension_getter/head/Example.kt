package sample

class Example {
    private val Int.adjusted: Int
        get() = this + if (Ab.enabled("feature")) 3 else 1
    fun run(x: Int): Int = x.adjusted
}
