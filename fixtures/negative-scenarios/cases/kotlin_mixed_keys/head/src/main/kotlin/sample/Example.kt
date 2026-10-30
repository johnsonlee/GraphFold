package sample
class Example {
    fun run(x: Int): Int {
        val a = if (Keys.enabled("experiment")) x + 20 else x + 1
        val b = if (Keys.version() >= 2) x * 20 else x * 2
        return a + b
    }
}
