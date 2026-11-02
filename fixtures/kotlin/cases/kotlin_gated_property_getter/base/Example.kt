package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val box = object { val value: Int get() = x + 1 }; return box.value
        }
        return -1
    }
}
