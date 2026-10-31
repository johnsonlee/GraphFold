package sample
class Example {
    companion object { private const val LIMIT = 20 }
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 0; return x + LIMIT; }
}
