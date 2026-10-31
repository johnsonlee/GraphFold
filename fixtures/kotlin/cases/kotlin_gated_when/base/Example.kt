package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return when (x) { 0 -> 1; else -> x }
        }
        return -1
    }
}
