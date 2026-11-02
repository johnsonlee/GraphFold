package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val value: Any = if (x > 0) "abc" else x; return if (value is String) value.length else 0
        }
        return -1
    }
}
