package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return sequenceOf(x, 1, 2).filter { it > 0 }.sum()
        }
        return -1
    }
}
