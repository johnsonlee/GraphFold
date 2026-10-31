package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return x.let { it + 1 }
    }
}
