package sample
class Example {
    fun run(x: Int): Int {
        try {
            return java.io.StringReader("ab").use { reader -> reader.read() }
        } catch (error: java.io.IOException) { return -1 }
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
