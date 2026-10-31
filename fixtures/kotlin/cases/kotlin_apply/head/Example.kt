package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        return Box(x).apply { value += 2 }.value
    }
    private class Box(var value: Int)
}
