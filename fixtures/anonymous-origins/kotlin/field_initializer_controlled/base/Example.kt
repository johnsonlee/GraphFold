package sample
import java.util.function.IntUnaryOperator
class Example {
    private val action = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = if (Ab.enabled("feature")) value + 1 else value }
    fun run(x: Int): Int = action.applyAsInt(x)
    fun gate(): Boolean = Ab.enabled("feature")
}
