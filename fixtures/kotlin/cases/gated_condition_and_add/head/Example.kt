package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return if (x > 0 && x < 10) 1 else 0
        }
        return 0
    }
}
