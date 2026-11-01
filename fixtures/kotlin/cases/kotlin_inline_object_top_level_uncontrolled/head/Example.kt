package sample

object Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return 42
        return bump(x)
    }
}

private inline fun bump(x: Int): Int = x - 1
