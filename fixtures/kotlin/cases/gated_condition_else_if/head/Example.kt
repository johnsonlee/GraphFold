package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            if (x < 0) return 1
            else if (x >= 10) return 2
            else return 3
        }
        return 0
    }
}
