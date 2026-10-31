package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val value: Int? = if (x > 0) x else null; return if (value != null) 1 else 0
    }
}
