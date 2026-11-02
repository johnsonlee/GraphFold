package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            if (x < 0) return -1
            return x * 2
        }
        return 0
    }
}
