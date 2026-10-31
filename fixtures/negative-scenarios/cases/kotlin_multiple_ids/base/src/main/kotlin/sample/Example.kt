package sample
class Example {
    fun run(x: Int): Int {
        val a = if (Keys.enabled("first")) x + 10 else x + 1
        val b = if (Keys.enabled("second")) x * 10 else x * 2
        return a + b
    }
}
