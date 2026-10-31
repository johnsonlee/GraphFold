package sample
import java.util.function.IntUnaryOperator
class Example {
    companion object {
        private val action: IntUnaryOperator
        init { action = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = if (Ab.enabled("feature")) value + 2 else value } }
    }
    fun run(x: Int): Int = action.applyAsInt(x)
    fun gate(): Boolean = Ab.enabled("feature")
}
