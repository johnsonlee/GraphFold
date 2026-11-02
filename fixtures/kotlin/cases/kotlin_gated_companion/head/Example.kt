package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return adjust(x, 2)
        }
        return -1
    }
    companion object { private fun adjust(value: Int, amount: Int): Int = value + amount }
}
