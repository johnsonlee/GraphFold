package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val value = intArrayOf(x)
            try { value[0]++ } finally { value[0] += 1 }
            return value[0]
        }
        return 0
    }
}
