package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int {
        val outer = object : IntUnaryOperator { override fun applyAsInt(input: Int): Int {
            val inner = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 2 }
            return inner.applyAsInt(input)
        } }
        return outer.applyAsInt(x)
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
