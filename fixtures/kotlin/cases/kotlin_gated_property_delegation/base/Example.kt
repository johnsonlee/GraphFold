package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val value by lazy { x + 1 }; return value
        }
        return -1
    }
}
