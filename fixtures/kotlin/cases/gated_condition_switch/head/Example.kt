package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            when (x) {
                1 -> return 11
                2 -> return 20
                else -> return 30
            }
        }
        return 0
    }
}
