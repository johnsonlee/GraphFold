package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return sequenceOf(x, 1, 2).filter { it > 0 }.sum()
    }
}
