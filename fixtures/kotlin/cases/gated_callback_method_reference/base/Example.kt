package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val operation = java.util.function.IntUnaryOperator(Math::abs)
            return operation.applyAsInt(x)
        }
        return 0
    }
}
