package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val (first, second) = Point(x, 1); return first + second
    }
    private data class Point(val x: Int, val y: Int)
}
