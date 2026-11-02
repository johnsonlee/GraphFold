package sample

class Example {
    fun run(x: Int): Int {
        val offset = x
        val operation = java.util.function.IntUnaryOperator { value -> value + offset + 2 }
        return operation.applyAsInt(x)
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
