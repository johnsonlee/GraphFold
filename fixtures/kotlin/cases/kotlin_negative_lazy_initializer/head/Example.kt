package sample

class Example {
    fun run(x: Int): Int {
        val offset by lazy { if (Ab.enabled("feature")) 3 else 1 }
        return x + offset + offset
    }
}
