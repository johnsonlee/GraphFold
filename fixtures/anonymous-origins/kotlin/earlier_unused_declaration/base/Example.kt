package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int {
        val live = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 10 }
        return live.applyAsInt(x)
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
