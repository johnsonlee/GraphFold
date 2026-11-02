package sample
class Example {
    val value: Int get() = if (Ab.enabled("feature")) 2 else 0
    fun run(): Int = value
}
