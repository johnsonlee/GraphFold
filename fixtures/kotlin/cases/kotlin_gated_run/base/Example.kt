package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return x.run { this + 1 }
        }
        return -1
    }
}
