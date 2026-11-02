package sample

class Example {
    fun run(x: Int): Int {
        val operation = java.util.function.IntUnaryOperator { value -> value + 1 }
        return operation.applyAsInt(x)
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
