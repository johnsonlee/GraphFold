package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val operation = java.util.function.IntUnaryOperator { value -> value + 1 }
            return operation.applyAsInt(x)
        }
        return 0
    }
}
