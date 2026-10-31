package sample
class Example {
    fun run(value: Int): Int {
        val operation = { value + if (Ab.enabled("feature")) 1 else 0 }
        return operation()
    }
}
