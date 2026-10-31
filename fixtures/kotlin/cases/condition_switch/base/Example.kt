package sample

class Example {
    fun run(x: Int): Int {
        when (x) {
            1 -> return 10
            2 -> return 20
            else -> return 30
        }
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
