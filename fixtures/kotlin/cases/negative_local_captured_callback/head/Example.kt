package sample
class Example {
    fun run(value: Int, offset: Int): Int {
        val callback = java.util.function.IntUnaryOperator { item ->
            if (Ab.enabled("feature")) item + offset + 2 else item + offset
        }
        return callback.applyAsInt(value)
    }
}
