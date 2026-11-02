package sample
import java.util.function.IntUnaryOperator
class Example {
    private val action: IntUnaryOperator
    init { action = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 1 } }
    fun run(x: Int): Int = action.applyAsInt(x)
    fun gate(): Boolean = Ab.enabled("feature")
}
