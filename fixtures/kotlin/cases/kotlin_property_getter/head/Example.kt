package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val box = object { val value: Int get() = x + 2 }; return box.value
    }
}
