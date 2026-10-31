package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int {
        val live = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 10 }
        return live.applyAsInt(x)
    }
    private fun unused(): IntUnaryOperator = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 99 }
    fun gate(): Boolean = Ab.enabled("feature")
}
