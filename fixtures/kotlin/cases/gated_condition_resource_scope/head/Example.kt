package sample
class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            try {
                return java.io.StringReader("ab").use { reader -> reader.read(); reader.read() }
            } catch (error: java.io.IOException) { return -1 }
        }
        return 0
    }
}
