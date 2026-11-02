package sample

class Example {
    fun run(x: Int): Int = adjust(x)
    private fun adjust(x: Int, offset: Int = if (Ab.enabled("feature")) 2 else 1): Int = x + offset
}
