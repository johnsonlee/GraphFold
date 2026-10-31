package sample

class Example {
    fun run(x: Int): Int {
        return when (x) { 1 -> 11; 2 -> 20; else -> 30 }
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
