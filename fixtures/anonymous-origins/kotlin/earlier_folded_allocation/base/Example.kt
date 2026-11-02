package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int {
        return (object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 10 }).applyAsInt(x)
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
