package sample
class Example {
    fun run(x: Int): Int = if (Ab.enabled("feature")) 43 else x + 1
    fun gate(): Boolean = Ab.enabled("feature")
}
