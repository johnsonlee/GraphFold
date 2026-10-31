package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        var result = 0; for (value in 0 until x) result += value; return result
    }
}
