package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return x.adjust(1)
    }
    private fun Int.adjust(amount: Int): Int = this + amount
}
