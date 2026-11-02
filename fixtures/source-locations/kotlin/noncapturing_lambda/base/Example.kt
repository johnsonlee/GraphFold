package sample
class Example {
    fun gate() = Ab.enabled("feature")
    fun run(): Int {
        val operation = { 1 }
        return operation()
    }
}
