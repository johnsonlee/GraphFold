package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return bump(x)
        return x
    }
    private inline fun bump(x: Int): Int = x - 1
}
