package sample
class Example {
    fun run(x: Int): Int {
        return synchronized(this) { x + 1 }
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
