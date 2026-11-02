package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return x.adjust(2)
    }
    private fun Int.adjust(amount: Int): Int = this + amount
}
