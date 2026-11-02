package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            val operation = object : java.util.function.IntUnaryOperator {
                override fun applyAsInt(value: Int): Int { return value + 1 }
            }
            return operation.applyAsInt(x)
        }
        return 0
    }
}
