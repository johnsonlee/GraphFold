package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            if (x < 0) throw IllegalArgumentException()
            return x
        }
        return 0
    }
}
