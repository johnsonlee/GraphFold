package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) { object : Runnable { override fun run() {} } }; (object : Runnable { override fun run() {} }).run(); return x }
    fun gate(): Boolean = Ab.enabled("feature")
}
