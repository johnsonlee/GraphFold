package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return x.adjust(2)
        }
        return -1
    }
    private fun Int.adjust(amount: Int): Int = this + amount
}
