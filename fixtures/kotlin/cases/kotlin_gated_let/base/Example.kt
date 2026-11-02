package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return x.let { it + 1 }
        }
        return -1
    }
}
