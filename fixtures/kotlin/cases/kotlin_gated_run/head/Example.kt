package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return x.run { this + 2 }
        }
        return -1
    }
}
