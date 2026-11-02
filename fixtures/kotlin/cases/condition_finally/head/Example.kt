package sample

class Example {
    fun run(x: Int): Int {
        val value = intArrayOf(x)
        try { value[0]++ } finally { value[0] += 2 }
        return value[0]
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
