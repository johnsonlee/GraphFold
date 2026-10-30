package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val value by lazy { x + 1 }; return value
    }
}
