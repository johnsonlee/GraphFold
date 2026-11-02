package sample
class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return synchronized(this) { x + 2 }
        }
        return 0
    }
}
