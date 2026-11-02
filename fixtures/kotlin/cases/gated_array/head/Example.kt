package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return intArrayOf(x, 3)[1]; return x + 1; }
}
