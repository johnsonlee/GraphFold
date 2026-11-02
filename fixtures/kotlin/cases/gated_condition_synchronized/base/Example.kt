package sample
class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return synchronized(this) { x + 1 }
        }
        return 0
    }
}
