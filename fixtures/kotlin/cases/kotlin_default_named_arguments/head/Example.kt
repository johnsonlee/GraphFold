package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return adjust(amount = x, offset = 2)
    }
    private fun adjust(amount: Int, offset: Int = 1): Int = amount + offset
}
