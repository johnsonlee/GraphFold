package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return bump
        return x
    }
    private val bump: Int inline get() = 2
}
