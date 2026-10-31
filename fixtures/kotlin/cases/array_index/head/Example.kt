package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 0; return intArrayOf(x, 2)[1]; }
}
