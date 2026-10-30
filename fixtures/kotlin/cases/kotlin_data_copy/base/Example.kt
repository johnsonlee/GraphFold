package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return Point(x, 1).copy(y = 2).y
    }
    private data class Point(val x: Int, val y: Int)
}
