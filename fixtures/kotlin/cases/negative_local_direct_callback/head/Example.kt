package sample
class Example {
    fun run(value: Int): Int {
        val callback = java.util.function.IntUnaryOperator { item ->
            if (Ab.enabled("feature")) item + 2 else item * 2
        }
        return callback.applyAsInt(value)
    }
}
