package sample
class Example {
    fun run(x: Int): Int {
        val a = if (Keys.enabled("first")) x + 20 else x + 1
        val b = if (Keys.enabled("second")) x * 20 else x * 2
        return a + b
    }
}
