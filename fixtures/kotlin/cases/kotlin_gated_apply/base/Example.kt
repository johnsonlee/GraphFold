package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return Box(x).apply { value += 1 }.value
        }
        return -1
    }
    private class Box(var value: Int)
}
