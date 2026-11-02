package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val offset = x
            val operation = java.util.function.IntUnaryOperator { value -> value + offset + 2 }
            return operation.applyAsInt(x)
        }
        return 0
    }
}
