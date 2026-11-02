package sample
class Example {
    fun run(x: Int): Int { (object : Runnable { override fun run() {} }).run(); return x }
    fun gate(): Boolean = Ab.enabled("feature")
}
