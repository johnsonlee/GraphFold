package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return if (x >= 0) 1 else 2
        }
        return 0
    }
}
