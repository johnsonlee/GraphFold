package sample
class Example {
    fun run(x: Int): Int {
        val group = Keys.group("experiment")
        if (group == 0 || group == -1) return x + 1
        return x + 10
    }
}
