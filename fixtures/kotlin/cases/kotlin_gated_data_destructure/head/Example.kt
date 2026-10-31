package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val (first, second) = Point(x, 2); return first + second
        }
        return -1
    }
    private data class Point(val x: Int, val y: Int)
}
