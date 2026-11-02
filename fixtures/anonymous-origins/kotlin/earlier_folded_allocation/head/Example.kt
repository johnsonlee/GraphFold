package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) { (object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 99 }).applyAsInt(x) }
        return (object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 10 }).applyAsInt(x)
    }
    fun gate(): Boolean = Ab.enabled("feature")
}
