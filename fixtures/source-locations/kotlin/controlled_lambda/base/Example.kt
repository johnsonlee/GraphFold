package sample
class Example {
    fun run(): Int {
        val operation = { if (Ab.enabled("feature")) 1 else 0 }
        return operation()
    }
}
