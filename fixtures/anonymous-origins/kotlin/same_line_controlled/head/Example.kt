package sample
import java.util.function.IntUnaryOperator
class Example {
    fun run(x: Int): Int { val first = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = value + 100 }; val second = object : IntUnaryOperator { override fun applyAsInt(value: Int): Int = if (Ab.enabled("feature")) value + 2 else value }; return first.applyAsInt(x) + second.applyAsInt(x) }
    fun gate(): Boolean = Ab.enabled("feature")
}
