package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val operation = ::first
        return operation(x)
    }

    private fun first(x: Int): Int = x + 1
    private fun second(x: Int): Int = x + 2
}
