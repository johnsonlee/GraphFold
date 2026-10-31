package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return when (x) { 0 -> 2; else -> x }
    }
}
