package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return if (x > 0 || x == -1) 1 else 0
        }
        return 0
    }
}
