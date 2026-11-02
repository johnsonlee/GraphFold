package sample
class Example {
    fun gate() = Ab.enabled("feature")
    fun run(value: Int): Int {
        val operation = { value + 2 }
        return operation()
    }
}
