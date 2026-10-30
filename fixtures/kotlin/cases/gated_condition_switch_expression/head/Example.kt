package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return when (x) { 1 -> 11; 2 -> 20; else -> 30 }
        }
        return 0
    }
}
