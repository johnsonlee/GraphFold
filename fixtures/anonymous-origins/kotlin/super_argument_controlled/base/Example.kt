package sample
class Example {
    open class Base(private val delta: Int) { open fun applyAsInt(x: Int): Int = x + delta }
    fun run(x: Int): Int { if (!Ab.enabled("feature")) return x; return (object : Base(1) { override fun applyAsInt(value: Int): Int = super.applyAsInt(value) }).applyAsInt(x) }
    fun gate(): Boolean = Ab.enabled("feature")
}
