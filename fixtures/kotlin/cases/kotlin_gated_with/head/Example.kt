package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return with(x) { this + 2 }
        }
        return -1
    }
}
