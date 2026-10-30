package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return adjust(x, 1)
    }
    companion object { private fun adjust(value: Int, amount: Int): Int = value + amount }
}
