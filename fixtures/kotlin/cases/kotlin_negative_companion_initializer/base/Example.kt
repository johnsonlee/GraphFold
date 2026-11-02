package sample

class Example {
    fun run(x: Int): Int = x + offset
    companion object {
        private val offset: Int = if (Ab.enabled("feature")) 2 else 1
    }
}
