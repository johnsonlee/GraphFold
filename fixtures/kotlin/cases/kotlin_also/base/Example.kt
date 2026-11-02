package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return Box(x).also { it.value += 1 }.value
    }
    private class Box(var value: Int)
}
