package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val text: String? = if (x > 0) "abc" else null; return text?.length ?: 2
        }
        return -1
    }
}
